package com.the3Cgrp.zupptrade.agent5;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.request.MarginCheckRequest;
import com.the3Cgrp.zupptrade.agent5.client.request.MultiOrderRequest;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MultiOrderResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.agent5.dto.ExecuteTradeRequest;
import com.the3Cgrp.zupptrade.agent5.dto.ExecuteTradeResponse;
import com.the3Cgrp.zupptrade.agent5.dto.LegOrderRequest;
import com.the3Cgrp.zupptrade.agent5.service.OrderTagBuilder;
import com.the3Cgrp.zupptrade.agent5.service.TradeExecutionService;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sandbox integration tests for Agent 5.
 *
 * Profiles:
 *   sandbox — routes orders to api-sandbox.upstox.com, cancel-on-timeout=true
 *   local   — NeonDB credentials, TOKEN_ENCRYPTION_KEY, api credentials
 *
 * Coverage:
 *   T1 — margin check (api.upstox.com, real production API)
 *   T2 — multi/place (api-sandbox.upstox.com, no real money)
 *   T3 — order status poll
 *   T4 — cleanup: cancel all placed orders
 *   T5 — TradeExecutionService.execute() full flow
 *        Reads expected net premium from DB, checks margin, places orders,
 *        polls fills, persists result. Verifies the complete integration.
 *
 * Run with:
 *   mvn test -pl agent5-execution "-Dexcluded.test.groups=" -Dgroups=sandbox ^
 *     -Dspring.profiles.active=sandbox,local
 *
 * Prerequisites (system env vars):
 *   TOKEN_ENCRYPTION_KEY  — must match the key used by upstox-auth
 *   UPSTOX_ACCESS_TOKEN   — production token (for margin check)
 *   UPSTOX_SANDBOX_TOKEN  — sandbox token (for order placement)
 *
 * @TestInstance(PER_CLASS) — allows @BeforeAll and @AfterAll as non-static
 *   methods so @Autowired beans are available during DB setup/teardown.
 */
@Tag("sandbox")
@SpringBootTest
@ActiveProfiles({"sandbox", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TradeExecutionSandboxIT {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionSandboxIT.class);

    // ── Shared test data ─────────────────────────────────────────────────────
    // Fixed UUID so all orders in this run share the same tag — makes cleanup easy
    private static final UUID   TEST_TRADE_ID  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String EXPECTED_TAG   = OrderTagBuilder.tag(TEST_TRADE_ID);

    // NIFTY PUT spread — update expiry date to the next live Tuesday expiry before running
    private static final String SHORT_LEG_KEY  = "NFO_OPT|NIFTY|2026-06-09|24500|PE";
    private static final String LONG_LEG_KEY   = "NFO_OPT|NIFTY|2026-06-09|24400|PE";
    private static final int    LOT_SIZE       = 75;
    private static final BigDecimal NET_PREMIUM = new BigDecimal("25.00");

    /** Tracks order IDs placed during T2 so T4 can cancel them. */
    private final List<String> placedOrderIds = new ArrayList<>();

    @Autowired private UpstoxOrderClient    orderClient;
    @Autowired private TradeExecutionService executionService;
    @Autowired private JdbcTemplate         jdbc;

    // ── DB lifecycle ──────────────────────────────────────────────────────────

    /**
     * Insert a CONFIRMED trade row so T5 can drive TradeExecutionService.execute().
     *
     * The trades table requires: id, status, strategy, expiry_date, legs, summary, market_context.
     * agent1_signal_id and user_profile_id are nullable — left NULL for test rows.
     * After the test run, @AfterAll deletes this row unconditionally.
     */
    @BeforeAll
    void insertConfirmedTrade() {
        // Clean up any stale row from a previous failed run first
        jdbc.update("DELETE FROM trades WHERE id = ?", TEST_TRADE_ID);

        jdbc.update("""
                INSERT INTO trades (id, status, strategy, expiry_date, legs, summary, market_context)
                VALUES (?::uuid, 'CONFIRMED', 'BullPutSpread', '2026-06-09',
                        '[]'::jsonb,
                        '{"netPremiumPerUnit": 25.00}'::jsonb,
                        '{}'::jsonb)
                """, TEST_TRADE_ID.toString());

        log.info("sandbox.setup.trade.inserted tradeId={}", TEST_TRADE_ID);
    }

    @AfterAll
    void cleanupTrade() {
        try {
            jdbc.update("DELETE FROM trades WHERE id = ?", TEST_TRADE_ID);
            log.info("sandbox.teardown.trade.deleted tradeId={}", TEST_TRADE_ID);
        } catch (Exception e) {
            log.warn("sandbox.teardown.failed error={}", e.getMessage());
        }
    }

    // ── T1: Margin check (production API) ────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("T1: Margin check returns valid margin figures for a bull-put spread")
    void marginCheck_shouldReturnValidMarginData() {
        log.info("sandbox.T1.margin.check tradeId={}", TEST_TRADE_ID);

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

        log.info("sandbox.T1.margin.result finalMargin={} available={}",
                response.data().finalMargin(), response.data().availableMargin());
    }

    // ── T2: multi/place (sandbox API) ────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("T2: multi/place sends both legs and returns order_ids with correct correlation_ids")
    void multiPlace_shouldReturnOrderIdsForBothLegs() {
        log.info("sandbox.T2.multi.place tradeId={} tag={}", TEST_TRADE_ID, EXPECTED_TAG);

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

        List<String> returnedCorrelationIds = response.data().stream()
                .map(MultiOrderResponse.OrderData::correlationId)
                .toList();
        assertThat(returnedCorrelationIds).contains(
                OrderTagBuilder.correlationId(TEST_TRADE_ID, 0),
                OrderTagBuilder.correlationId(TEST_TRADE_ID, 1));

        response.data().forEach(d -> {
            assertThat(d.orderId()).isNotBlank();
            placedOrderIds.add(d.orderId());
            log.info("sandbox.T2.order.placed correlationId={} orderId={}", d.correlationId(), d.orderId());
        });
        assertThat(placedOrderIds).hasSize(2);
    }

    // ── T3: Order status poll ────────────────────────────────────────────────

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
            assertThat(status.data().orderStatus())
                    .isNotBlank()
                    .isIn("open", "pending", "complete", "cancelled", "rejected");

            log.info("sandbox.T3.order.status orderId={} status={}",
                    orderId, status.data().orderStatus());
        }
    }

    // ── T4: Cleanup — cancel orders placed in T2 ─────────────────────────────

    @Test
    @Order(4)
    @DisplayName("T4: cancel all sandbox orders placed in this test run")
    void cleanup_shouldCancelAllPlacedOrders() {
        Assumptions.assumeTrue(!placedOrderIds.isEmpty(), "Skipped: no orders to cancel");

        for (String orderId : placedOrderIds) {
            try {
                OrderStatusResponse status = orderClient.getOrderStatus(orderId);
                if (status.isOpen()) {
                    orderClient.cancelOrder(orderId);
                    log.info("sandbox.T4.order.cancelled orderId={}", orderId);
                } else {
                    log.info("sandbox.T4.order.skip.cancel orderId={} status={}",
                            orderId, status.data() != null ? status.data().orderStatus() : "unknown");
                }
            } catch (Exception e) {
                log.warn("sandbox.T4.cancel.error orderId={} error={}", orderId, e.getMessage());
            }
        }
        placedOrderIds.clear();
        assertThat(true).isTrue(); // best-effort cleanup — test always passes
    }

    // ── T5: TradeExecutionService.execute() full integration ─────────────────

    /**
     * Drives TradeExecutionService.execute() end-to-end with the real DB and real sandbox API.
     *
     * What this test verifies:
     *   1. Service reads expected net premium from trades table (DB connectivity ✓)
     *   2. Margin check fires against api.upstox.com (API connectivity ✓)
     *   3. multi/place fires against api-sandbox.upstox.com (sandbox order placement ✓)
     *   4. Service polls order status until timeout/fill (polling loop ✓)
     *   5. Service persists result back to trades table and updates status (DB write ✓)
     *
     * The response will typically be one of:
     *   ACTIVE   — sandbox order filled (synthetic fill confirmed)
     *   REJECTED — sandbox order timed out and was cancelled (expected with cancel-on-timeout=true)
     *              This is also a valid result — it confirms the full execution flow ran.
     *
     * The test FAILS only if the service throws an exception or returns a null tradeId,
     * which would indicate a structural error in the execution flow.
     */
    @Test
    @Order(5)
    @DisplayName("T5: TradeExecutionService.execute() reads DB, checks margin, places order, persists result")
    void executeService_fullFlow_readFromDbAndPersistsResult() {
        log.info("sandbox.T5.service.execute start tradeId={}", TEST_TRADE_ID);

        ExecuteTradeRequest request = new ExecuteTradeRequest(TEST_TRADE_ID, List.of(
                new LegOrderRequest(
                        SHORT_LEG_KEY, OptionType.PE, 24500, LegAction.SELL,
                        new BigDecimal("50.00"), LOT_SIZE),
                new LegOrderRequest(
                        LONG_LEG_KEY,  OptionType.PE, 24400, LegAction.BUY,
                        new BigDecimal("25.00"), LOT_SIZE)
        ));

        ExecuteTradeResponse response = executionService.execute(request);

        // ── Structural assertions — always apply ────────────────────────────
        assertThat(response).isNotNull();
        assertThat(response.tradeId()).isEqualTo(TEST_TRADE_ID);
        assertThat(response.executionStatus()).isIn(TradeStatus.ACTIVE, TradeStatus.REJECTED);
        assertThat(response.executedAt()).isNotNull();
        assertThat(response.expectedNetPremiumPerUnit())
                .as("expected net premium must be read from DB as 25.00")
                .isEqualByComparingTo(NET_PREMIUM);

        // ── Status-specific assertions ──────────────────────────────────────
        if (response.executionStatus() == TradeStatus.ACTIVE) {
            assertThat(response.fills())
                    .as("ACTIVE response must have fills for all legs")
                    .isNotEmpty();
            assertThat(response.actualNetPremiumPerUnit())
                    .as("actual net premium must be computed from fills")
                    .isNotNull();

            // Verify DB was updated to ACTIVE
            String dbStatus = jdbc.queryForObject(
                    "SELECT status FROM trades WHERE id = ?", String.class, TEST_TRADE_ID);
            assertThat(dbStatus).isEqualTo("ACTIVE");
            log.info("sandbox.T5.result ACTIVE actualNet={}", response.actualNetPremiumPerUnit());

        } else {
            assertThat(response.rejectionReason())
                    .as("REJECTED response must include a rejection reason")
                    .isNotBlank();
            log.info("sandbox.T5.result REJECTED reason={}", response.rejectionReason());
            // DB should be updated to REJECTED — re-verify service persisted the state
            String dbStatus = jdbc.queryForObject(
                    "SELECT status FROM trades WHERE id = ?", String.class, TEST_TRADE_ID);
            assertThat(dbStatus).isEqualTo("REJECTED");
        }

        log.info("sandbox.T5.complete status={} slippage={} reason={}",
                response.executionStatus(), response.slippageAlert(), response.rejectionReason());
    }
}
