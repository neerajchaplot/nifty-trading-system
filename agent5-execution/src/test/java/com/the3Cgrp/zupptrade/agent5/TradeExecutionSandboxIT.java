package com.the3Cgrp.zupptrade.agent5;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.request.MarginCheckRequest;
import com.the3Cgrp.zupptrade.agent5.client.request.MultiOrderRequest;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MultiOrderResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.agent5.service.OrderTagBuilder;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sandbox integration tests for Agent 5 Upstox order operations.
 *
 * Profile: sandbox
 *   - Margin check → api.upstox.com        (real production API, UPSTOX_ACCESS_TOKEN)
 *   - Order placement → api-sandbox.upstox.com (UPSTOX_SANDBOX_TOKEN, no real money)
 *
 * Run with:
 *   mvn test -pl agent5-execution -Dspring.profiles.active=sandbox -Dgroups=sandbox
 *
 * Prerequisites:
 *   System env vars:
 *     UPSTOX_ACCESS_TOKEN    — production token (for margin check)
 *     UPSTOX_SANDBOX_TOKEN   — sandbox token (for order placement)
 *     DB_USER / DB_PASSWORD  — PostgreSQL credentials
 *
 * These tests place REAL sandbox orders and cancel them on cleanup.
 * No real money is involved.
 *
 * NOTE: Sandbox orders may not fill in the same way as production (synthetic fills).
 * The tests verify API connectivity, request/response structure, and tag queryability.
 */
@Tag("sandbox")
@SpringBootTest
@ActiveProfiles("sandbox")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TradeExecutionSandboxIT {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionSandboxIT.class);

    // Use a fixed test trade ID so all orders in this run share the same tag
    private static final UUID TEST_TRADE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String EXPECTED_TAG = OrderTagBuilder.tag(TEST_TRADE_ID);

    // NIFTY option instrument keys — use a near-expiry strike that exists on sandbox
    // These are representative values; adjust expiry to the next Tuesday if needed
    private static final String SHORT_LEG_KEY = "NFO_OPT|NIFTY|2026-06-10|24500|PE";
    private static final String LONG_LEG_KEY  = "NFO_OPT|NIFTY|2026-06-10|24400|PE";
    private static final int LOT_SIZE = 75;

    // Track placed order IDs for cleanup
    private static final List<String> placedOrderIds = new ArrayList<>();

    @Autowired
    private UpstoxOrderClient orderClient;

    // ── Test 1: Margin Check (real Upstox production API) ───────────────────

    @Test
    @Order(1)
    @DisplayName("T1: Margin check returns valid margin figures for a bull-put spread")
    void marginCheck_shouldReturnValidMarginData() {
        log.info("sandbox.test.margin.check tradeId={}", TEST_TRADE_ID);

        MarginCheckRequest request = new MarginCheckRequest(List.of(
                new MarginCheckRequest.Instrument(SHORT_LEG_KEY, "SELL", LOT_SIZE, "D", new BigDecimal("50.00")),
                new MarginCheckRequest.Instrument(LONG_LEG_KEY,  "BUY",  LOT_SIZE, "D", new BigDecimal("25.00"))
        ));

        MarginCheckResponse response = orderClient.checkMargin(request);

        assertThat(response).isNotNull();
        assertThat(response.isApiSuccess()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().finalMargin()).isNotNull().isGreaterThan(BigDecimal.ZERO);
        assertThat(response.data().availableMargin()).isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);

        log.info("sandbox.margin.result finalMargin={} available={}",
                response.data().finalMargin(), response.data().availableMargin());
    }

    // ── Test 2: Multi-order placement (sandbox API) ──────────────────────────

    @Test
    @Order(2)
    @DisplayName("T2: multi/place sends both legs and returns order_ids with correct correlation_ids")
    void multiPlace_shouldReturnOrderIdsForBothLegs() {
        log.info("sandbox.test.multi.place tradeId={} tag={}", TEST_TRADE_ID, EXPECTED_TAG);

        MultiOrderRequest request = new MultiOrderRequest(List.of(
                MultiOrderRequest.OrderLeg.limit(
                        SHORT_LEG_KEY, "SELL", "D", LOT_SIZE, new BigDecimal("50.00"),
                        EXPECTED_TAG, OrderTagBuilder.correlationId(TEST_TRADE_ID, 0)),
                MultiOrderRequest.OrderLeg.limit(
                        LONG_LEG_KEY, "BUY", "D", LOT_SIZE, new BigDecimal("25.00"),
                        EXPECTED_TAG, OrderTagBuilder.correlationId(TEST_TRADE_ID, 1))
        ));

        MultiOrderResponse response = orderClient.placeMultiOrder(request);

        assertThat(response).isNotNull();
        assertThat(response.isApiSuccess()).isTrue();
        assertThat(response.summary()).isNotNull();
        assertThat(response.summary().payloadError()).isEqualTo(0);
        assertThat(response.summary().total()).isEqualTo(2);
        assertThat(response.data()).isNotNull().hasSize(2);

        // Verify correlation_ids match what we sent
        List<String> returnedCorrelationIds = response.data().stream()
                .map(MultiOrderResponse.OrderData::correlationId)
                .toList();
        assertThat(returnedCorrelationIds).contains(
                OrderTagBuilder.correlationId(TEST_TRADE_ID, 0),
                OrderTagBuilder.correlationId(TEST_TRADE_ID, 1));

        // Verify order_ids are non-null
        response.data().forEach(d -> {
            assertThat(d.orderId()).isNotBlank();
            placedOrderIds.add(d.orderId());
            log.info("sandbox.order.placed correlationId={} orderId={}", d.correlationId(), d.orderId());
        });

        assertThat(placedOrderIds).hasSize(2);
    }

    // ── Test 3: Order status poll ────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("T3: order status returns valid status for each placed order")
    void orderStatus_shouldReturnStatusForPlacedOrders() {
        Assumptions.assumeTrue(!placedOrderIds.isEmpty(),
                "Skipped: T2 must run first to have order IDs");

        for (String orderId : placedOrderIds) {
            OrderStatusResponse status = orderClient.getOrderStatus(orderId);

            assertThat(status).isNotNull();
            assertThat(status.data()).isNotNull();
            assertThat(status.data().orderId()).isEqualTo(orderId);
            // Sandbox orders are typically open or complete
            assertThat(status.data().orderStatus())
                    .isNotBlank()
                    .isIn("open", "pending", "complete", "cancelled", "rejected");

            log.info("sandbox.order.status orderId={} status={}",
                    orderId, status.data().orderStatus());
        }
    }

    // ── Test 4: Cleanup — cancel all placed orders ───────────────────────────

    @Test
    @Order(4)
    @DisplayName("T4: cancel all sandbox orders placed in this test run")
    void cleanup_shouldCancelAllPlacedOrders() {
        Assumptions.assumeTrue(!placedOrderIds.isEmpty(),
                "Skipped: no orders to cancel");

        for (String orderId : placedOrderIds) {
            try {
                // Only cancel if still open — don't error on already-filled/cancelled
                OrderStatusResponse status = orderClient.getOrderStatus(orderId);
                if (status.isOpen()) {
                    orderClient.cancelOrder(orderId);
                    log.info("sandbox.order.cancelled orderId={}", orderId);
                } else {
                    log.info("sandbox.order.skip.cancel orderId={} status={}",
                            orderId, status.data() != null ? status.data().orderStatus() : "unknown");
                }
            } catch (Exception e) {
                log.warn("sandbox.cancel.error orderId={} error={}", orderId, e.getMessage());
            }
        }
        placedOrderIds.clear();
        // Test passes regardless — cleanup is best-effort
        assertThat(true).isTrue();
    }
}
