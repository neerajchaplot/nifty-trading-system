package com.the3Cgrp.zupptrade.agent5.service;

import tools.jackson.databind.json.JsonMapper;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient.UpstoxOrderException;
import com.the3Cgrp.zupptrade.agent5.client.response.FundsAndMarginResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse.MarginData;
import com.the3Cgrp.zupptrade.agent5.client.response.MultiOrderResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MultiOrderResponse.Summary;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.agent5.config.Agent5ExecutionProperties;
import com.the3Cgrp.zupptrade.agent5.client.request.MultiOrderRequest;
import com.the3Cgrp.zupptrade.agent5.dto.ExecuteTradeRequest;
import com.the3Cgrp.zupptrade.agent5.dto.ExecuteTradeResponse;
import com.the3Cgrp.zupptrade.agent5.dto.ExitTradeResponse;
import com.the3Cgrp.zupptrade.agent5.dto.LegOrderRequest;
import com.the3Cgrp.zupptrade.shared.dto.ExitTradeRequest;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TradeExecutionService — no Spring context, no DB, no Upstox.
 *
 * All external dependencies (UpstoxOrderClient, JdbcTemplate) are mocked.
 * Agent5ExecutionProperties is instantiated directly with short timeouts
 * so polling/timeout tests complete in milliseconds.
 *
 * Note: MultiOrderResponse.OrderData and OrderStatusResponse.OrderData both
 * have the inner class name "OrderData" — they are referenced using the outer
 * class as qualifier throughout this file to avoid import collision.
 */
@ExtendWith(MockitoExtension.class)
class TradeExecutionServiceTest {

    @Mock private UpstoxOrderClient   orderClient;
    @Mock private JdbcTemplate        jdbc;
    @Mock private AlertService        alertService;
    @Mock private TradeLedgerService  ledger;

    private Agent5ExecutionProperties props;
    private TradeExecutionService     service;

    private static final UUID       TRADE_ID     = UUID.fromString("00000000-1111-0000-0000-000000000001");
    private static final BigDecimal EXPECTED_NET = new BigDecimal("25.00");

    @BeforeEach
    void setUp() {
        props = new Agent5ExecutionProperties();
        props.setProduct("D");
        props.setSlippageAlertThreshold(new BigDecimal("0.10"));
        props.setFillPollIntervalMs(10);     // fast polling — unit tests should not sleep long
        props.setFillTimeoutMs(100);         // short timeout so timeout tests finish quickly
        props.setCancelOnTimeoutInsteadOfMarket(true); // cancel on timeout (sandbox behaviour)

        service = new TradeExecutionService(orderClient, props, jdbc, JsonMapper.builder().build(), alertService, ledger);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void execute_allLegsFilledNoSlippage_returnsActive() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenMultiOrderPlaced("ORDER_SELL", "ORDER_BUY");
        // actual net = 50.50 - 24.80 = 25.70 > expected 25.00 * 0.90 = 22.50 → no slippage
        givenOrderComplete("ORDER_SELL", "SELL", 75, new BigDecimal("50.50"));
        givenOrderComplete("ORDER_BUY",  "BUY",  75, new BigDecimal("24.80"));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.ACTIVE);
        assertThat(response.slippageAlert()).isFalse();
        assertThat(response.fills()).hasSize(2);
        assertThat(response.actualNetPremiumPerUnit()).isEqualByComparingTo(new BigDecimal("25.70"));
        // DB should have been updated to ACTIVE
        verify(jdbc).update(contains("status = 'ACTIVE'"), anyString(), eq(TRADE_ID));
    }

    @Test
    void execute_actualNetBelowThreshold_activeWithSlippageAlert() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenMultiOrderPlaced("ORDER_SELL", "ORDER_BUY");
        // actual net = 30.00 - 8.00 = 22.00 < 25.00 * (1 - 0.10) = 22.50 → slippage
        givenOrderComplete("ORDER_SELL", "SELL", 75, new BigDecimal("30.00"));
        givenOrderComplete("ORDER_BUY",  "BUY",  75, new BigDecimal("8.00"));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.ACTIVE);
        assertThat(response.slippageAlert()).isTrue();
        assertThat(response.slippageMessage()).isNotBlank();
    }

    // ── Trade not found ───────────────────────────────────────────────────────

    @Test
    void execute_tradeNotFoundInDb_returnsRejectedImmediately() {
        // queryForObject returns null → trade not CONFIRMED
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any())).thenReturn(null);

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(response.rejectionReason()).containsIgnoringCase("not found");
        // Should not have called Upstox at all
        verifyNoInteractions(orderClient);
    }

    // ── Margin failures ───────────────────────────────────────────────────────

    @Test
    void execute_insufficientMargin_returnsRejected() {
        givenConfirmedTrade(EXPECTED_NET);
        // finalMargin = 75k, availableFunds = 50k → insufficient
        when(orderClient.checkMargin(any())).thenReturn(
                new MarginCheckResponse("success", new MarginData(new BigDecimal("80000"), new BigDecimal("75000"))));
        when(orderClient.getAvailableFunds()).thenReturn(
                new FundsAndMarginResponse("success", new FundsAndMarginResponse.FundsData(new BigDecimal("50000"))));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(response.rejectionReason()).containsIgnoringCase("margin");
        verify(orderClient, never()).placeMultiOrder(any());
    }

    @Test
    void execute_marginApiThrows_returnsRejected() {
        givenConfirmedTrade(EXPECTED_NET);
        when(orderClient.checkMargin(any()))
                .thenThrow(new UpstoxOrderException("503 Service Unavailable"));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(response.rejectionReason()).containsIgnoringCase("margin");
        verify(orderClient, never()).placeMultiOrder(any());
    }

    // ── Payload / exchange errors ─────────────────────────────────────────────

    @Test
    void execute_payloadError_returnsRejectedNothingPlaced() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        // payload_error > 0 → Upstox rejected the entire batch before sending to exchange
        MultiOrderResponse payloadError = new MultiOrderResponse(
                "success", List.of(), List.of(), new Summary(2, 0, 0, 2));
        when(orderClient.placeMultiOrder(any())).thenReturn(payloadError);

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(response.rejectionReason()).containsIgnoringCase("payload error");
        // Nothing was sent to exchange — no cancellations needed
        verify(orderClient, never()).cancelOrder(any());
    }

    @Test
    void execute_exchangeRejectsOneLeg_cancelsAcceptedLegAndReturnsRejected() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        // Exchange accepts short leg (correlation index 0) but rejects long leg (index 1)
        String corrId0 = OrderTagBuilder.correlationId(TRADE_ID, 0);
        String corrId1 = OrderTagBuilder.correlationId(TRADE_ID, 1);
        MultiOrderResponse partialAccept = new MultiOrderResponse(
                "success",
                List.of(new MultiOrderResponse.OrderData(corrId0, "ORDER_SELL")),
                List.of(new MultiOrderResponse.OrderError(corrId1, "RISK001", "Risk check failed")),
                new Summary(2, 1, 1, 0));
        when(orderClient.placeMultiOrder(any())).thenReturn(partialAccept);

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        // The accepted ORDER_SELL must be cancelled — cannot leave a naked short
        verify(orderClient).cancelOrder("ORDER_SELL");
    }

    // ── Fill timeout ──────────────────────────────────────────────────────────

    @Test
    void execute_fillTimesOut_cancelAndReturnsRejected() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenMultiOrderPlaced("ORDER_SELL", "ORDER_BUY");
        // Order stays "open" indefinitely — 100ms timeout fires quickly
        OrderStatusResponse open = new OrderStatusResponse("success",
                new OrderStatusResponse.OrderData(
                        "ORDER_SELL", "open", null, "SELL", 75, 0, 75, null));
        when(orderClient.getOrderStatus(anyString())).thenReturn(open);

        ExecuteTradeResponse response = service.execute(buildRequest());

        // cancelOnTimeoutInsteadOfMarket=true (set in setUp) → cancel is called
        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        verify(orderClient, atLeastOnce()).cancelOrder(any());
    }

    // ── Correlation ID / tag assignment ──────────────────────────────────────

    @Test
    void execute_multiOrderUsesCorrectTagAndCorrelationIds() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenMultiOrderPlaced("ORDER_SELL", "ORDER_BUY");
        givenOrderComplete("ORDER_SELL", "SELL", 75, new BigDecimal("50.00"));
        givenOrderComplete("ORDER_BUY",  "BUY",  75, new BigDecimal("25.00"));

        service.execute(buildRequest());

        String expectedTag  = OrderTagBuilder.tag(TRADE_ID);
        String expectedCor0 = OrderTagBuilder.correlationId(TRADE_ID, 0);
        String expectedCor1 = OrderTagBuilder.correlationId(TRADE_ID, 1);

        verify(orderClient).placeMultiOrder(argThat(req ->
                req.orders().stream().anyMatch(leg -> leg.correlationId().equals(expectedCor0)) &&
                req.orders().stream().anyMatch(leg -> leg.correlationId().equals(expectedCor1)) &&
                req.orders().stream().allMatch(leg -> leg.tag().equals(expectedTag))
        ));
    }

    // ── DB update verification ─────────────────────────────────────────────

    @Test
    void execute_rejected_updatesTradeStatusToRejected() {
        // Trigger rejection via insufficient margin (finalMargin = 75k, available = 10k)
        givenConfirmedTrade(EXPECTED_NET);
        when(orderClient.checkMargin(any())).thenReturn(
                new MarginCheckResponse("success", new MarginData(new BigDecimal("80000"), new BigDecimal("75000"))));
        when(orderClient.getAvailableFunds()).thenReturn(
                new FundsAndMarginResponse("success", new FundsAndMarginResponse.FundsData(new BigDecimal("10000"))));

        service.execute(buildRequest());

        // DB must record the REJECTED status
        verify(jdbc).update(contains("status = 'REJECTED'"), anyString(), eq(TRADE_ID));
    }

    // ── Exit — happy path ─────────────────────────────────────────────────────

    @Test
    void exit_activeStatus_placesReverseMarketOrderAndReturnsClosed() {
        givenCurrentTradeStatus("ACTIVE");
        when(orderClient.placeMultiOrder(any())).thenReturn(exitOrderSuccess());

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.status()).isEqualTo(TradeStatus.CLOSED);
        assertThat(response.failureReason()).isNull();
        assertThat(response.closedAt()).isNotNull();
        verify(orderClient).placeMultiOrder(any());
    }

    @Test
    void exit_exitInProgressStatus_proceedsAndReturnsClosed() {
        // EXIT_IN_PROGRESS = Agent 3 already set the guard; Agent 5 retries the placement
        givenCurrentTradeStatus("EXIT_IN_PROGRESS");
        when(orderClient.placeMultiOrder(any())).thenReturn(exitOrderSuccess());

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.status()).isEqualTo(TradeStatus.CLOSED);
        verify(orderClient).placeMultiOrder(any());
    }

    @Test
    void exit_exitFailedStatus_proceedsAndReturnsClosed() {
        // EXIT_FAILED = previous cycle failed; Agent 3 retries on next cycle
        givenCurrentTradeStatus("EXIT_FAILED");
        when(orderClient.placeMultiOrder(any())).thenReturn(exitOrderSuccess());

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.status()).isEqualTo(TradeStatus.CLOSED);
        verify(orderClient).placeMultiOrder(any());
    }

    // ── Exit — non-eligible statuses (early return) ───────────────────────────

    @Test
    void exit_closedStatus_returnsEarlyNoOrderPlaced() {
        givenCurrentTradeStatus("CLOSED");

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.failureReason()).containsIgnoringCase("not in exit-eligible");
        verifyNoInteractions(orderClient);
    }

    @Test
    void exit_rejectedStatus_returnsEarlyNoOrderPlaced() {
        givenCurrentTradeStatus("REJECTED");

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.failureReason()).containsIgnoringCase("not in exit-eligible");
        verifyNoInteractions(orderClient);
    }

    @Test
    void exit_tradeNotFoundInDb_returnsEarlyNoOrderPlaced() {
        when(jdbc.queryForObject(anyString(), eq(String.class), any())).thenReturn(null);

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.failureReason()).containsIgnoringCase("not in exit-eligible");
        verifyNoInteractions(orderClient);
    }

    // ── Exit — failure paths ──────────────────────────────────────────────────

    @Test
    void exit_orderPlacementFails_setsExitFailedAndAlerts() {
        givenCurrentTradeStatus("ACTIVE");
        when(orderClient.placeMultiOrder(any()))
                .thenThrow(new UpstoxOrderException("connection refused"));

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.status()).isEqualTo(TradeStatus.EXIT_FAILED);
        assertThat(response.failureReason()).isNotBlank();
        // Critical alert must fire — user must know position may still be open
        verify(alertService).critical(eq(TRADE_ID), anyString(), anyString());
    }

    @Test
    void exit_payloadError_setsExitFailedAndAlerts() {
        givenCurrentTradeStatus("ACTIVE");
        MultiOrderResponse payloadErr = new MultiOrderResponse(
                "success", List.of(), List.of(), new Summary(2, 0, 0, 2));
        when(orderClient.placeMultiOrder(any())).thenReturn(payloadErr);

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.status()).isEqualTo(TradeStatus.EXIT_FAILED);
        verify(alertService).critical(eq(TRADE_ID), anyString(), anyString());
    }

    // ── Exit — action reversal ────────────────────────────────────────────────

    @Test
    void exit_reversesLegActions_sellLegGetsBuyOrder_buyLegGetsSellOrder() {
        givenCurrentTradeStatus("ACTIVE");
        ArgumentCaptor<MultiOrderRequest> captor = ArgumentCaptor.forClass(MultiOrderRequest.class);
        when(orderClient.placeMultiOrder(captor.capture())).thenReturn(exitOrderSuccess());

        // buildExitRequest: leg 0 = SELL, leg 1 = BUY — both must be reversed to MARKET
        service.exit(buildExitRequest());

        MultiOrderRequest placed = captor.getValue();
        assertThat(placed.orders()).hasSize(2);
        assertThat(placed.orders().get(0).transactionType()).isEqualTo("BUY");  // SELL → BUY
        assertThat(placed.orders().get(1).transactionType()).isEqualTo("SELL"); // BUY → SELL
        assertThat(placed.orders()).allMatch(leg -> "MARKET".equals(leg.orderType()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Makes jdbc.queryForObject return the expected net premium.
     * Uses any() for the tradeId vararg to avoid Mockito vararg matching issues.
     */
    private void givenConfirmedTrade(BigDecimal netPremium) {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any()))
                .thenReturn(netPremium);
    }

    private void givenSufficientMargin() {
        when(orderClient.checkMargin(any())).thenReturn(
                new MarginCheckResponse("success", new MarginData(new BigDecimal("80000"), new BigDecimal("75000"))));
        when(orderClient.getAvailableFunds()).thenReturn(
                new FundsAndMarginResponse("success", new FundsAndMarginResponse.FundsData(new BigDecimal("200000"))));
    }

    private void givenMultiOrderPlaced(String sellOrderId, String buyOrderId) {
        String corrId0 = OrderTagBuilder.correlationId(TRADE_ID, 0);
        String corrId1 = OrderTagBuilder.correlationId(TRADE_ID, 1);
        MultiOrderResponse placed = new MultiOrderResponse(
                "success",
                List.of(
                        new MultiOrderResponse.OrderData(corrId0, sellOrderId),
                        new MultiOrderResponse.OrderData(corrId1, buyOrderId)),
                List.of(),
                new Summary(2, 2, 0, 0));
        when(orderClient.placeMultiOrder(any())).thenReturn(placed);
    }

    private void givenOrderComplete(String orderId, String txType, int qty, BigDecimal avgPrice) {
        OrderStatusResponse complete = new OrderStatusResponse("success",
                new OrderStatusResponse.OrderData(orderId, "complete", null, txType, qty, qty, 0, avgPrice));
        when(orderClient.getOrderStatus(orderId)).thenReturn(complete);
    }

    private ExecuteTradeRequest buildRequest() {
        return new ExecuteTradeRequest(TRADE_ID, List.of(
                new LegOrderRequest(
                        "NFO_OPT|NIFTY|2026-06-09|24500|PE",
                        OptionType.PE, 24500, LegAction.SELL, new BigDecimal("50.00"), 75),
                new LegOrderRequest(
                        "NFO_OPT|NIFTY|2026-06-09|24400|PE",
                        OptionType.PE, 24400, LegAction.BUY,  new BigDecimal("25.00"), 75)
        ));
    }

    private void givenCurrentTradeStatus(String status) {
        when(jdbc.queryForObject(anyString(), eq(String.class), any())).thenReturn(status);
    }

    private ExitTradeRequest buildExitRequest() {
        return new ExitTradeRequest(TRADE_ID, "T3_EXIT_BREACH", List.of(
                new ExitTradeRequest.ExitLeg(
                        "NFO_OPT|NIFTY|2026-06-09|24500|PE", LegAction.SELL, 75),
                new ExitTradeRequest.ExitLeg(
                        "NFO_OPT|NIFTY|2026-06-09|24400|PE", LegAction.BUY, 75)
        ));
    }

    private MultiOrderResponse exitOrderSuccess() {
        return new MultiOrderResponse("success", List.of(), List.of(), new Summary(2, 2, 0, 0));
    }
}
