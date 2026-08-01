package com.the3Cgrp.zupptrade.agent5.service;

import tools.jackson.databind.json.JsonMapper;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient.UpstoxOrderException;
import com.the3Cgrp.zupptrade.agent5.client.request.PlaceOrderV3Request;
import com.the3Cgrp.zupptrade.agent5.client.response.FundsAndMarginResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse.MarginData;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.PlaceOrderV3Response;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse;
import com.the3Cgrp.zupptrade.agent5.config.Agent5ExecutionProperties;
import com.the3Cgrp.zupptrade.agent5.dto.ExecuteTradeRequest;
import com.the3Cgrp.zupptrade.agent5.dto.ExecuteTradeResponse;
import com.the3Cgrp.zupptrade.agent5.dto.ExitTradeResponse;
import com.the3Cgrp.zupptrade.agent5.dto.LegOrderRequest;
import com.the3Cgrp.zupptrade.shared.dto.ExitTradeRequest;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.core.alert.CriticalAlertService;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
 * V3 model: one order per leg via UpstoxOrderClient.placeOrder(PlaceOrderV3Request), returning a
 * PlaceOrderV3Response whose data.order_ids has exactly one id (we never slice). Each leg carries a
 * UNIQUE tag (OrderTagBuilder.entryTag) — the tag replaces v2's correlation_id.
 */
@ExtendWith(MockitoExtension.class)
class TradeExecutionServiceTest {

    @Mock private UpstoxOrderClient      orderClient;
    @Mock private JdbcTemplate           jdbc;
    @Mock private AlertService           alertService;
    @Mock private CriticalAlertService   criticalAlertService;
    @Mock private TradeLedgerService     ledger;

    private Agent5ExecutionProperties props;
    private TradeExecutionService     service;

    private static final UUID       TRADE_ID     = UUID.fromString("00000000-1111-0000-0000-000000000001");
    private static final BigDecimal EXPECTED_NET = new BigDecimal("25.00");

    // Credit spread leg keys (buildRequest): index 0 = short (SELL), index 1 = long (BUY)
    private static final String SHORT_KEY = "NFO_OPT|NIFTY|2026-06-09|24500|PE";
    private static final String LONG_KEY  = "NFO_OPT|NIFTY|2026-06-09|24400|PE";

    // Iron Condor keys (buildIronCondorRequest)
    private static final String PE_SHORT_KEY = "NFO_OPT|NIFTY|2026-06-09|24000|PE";
    private static final String PE_LONG_KEY  = "NFO_OPT|NIFTY|2026-06-09|23900|PE";
    private static final String CE_SHORT_KEY = "NFO_OPT|NIFTY|2026-06-09|25000|CE";
    private static final String CE_LONG_KEY  = "NFO_OPT|NIFTY|2026-06-09|25100|CE";

    // Debit spread (bull call) keys (buildDebitRequest): index 0 = short (SELL), index 1 = long (BUY)
    private static final String CE_LONG_DEBIT_KEY  = "NFO_OPT|NIFTY|2026-06-09|24500|CE";  // BUY
    private static final String CE_SHORT_DEBIT_KEY = "NFO_OPT|NIFTY|2026-06-09|24600|CE";  // SELL

    @BeforeEach
    void setUp() {
        props = new Agent5ExecutionProperties();
        props.setProduct("D");
        props.setSlippageAlertThreshold(new BigDecimal("0.10"));
        props.setFillPollIntervalMs(10);
        props.setFillTimeoutMs(100);
        props.setCancelOnTimeoutInsteadOfMarket(true);
        props.setReconcileDelayMs(5);
        props.setMaxOrderQuantity(1755);

        service = new TradeExecutionService(orderClient, props, jdbc, JsonMapper.builder().build(),
                alertService, criticalAlertService, ledger);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void execute_allLegsFilledNoSlippage_returnsActive() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenPlacementAccepted();
        givenOrderComplete(orderId(1), 75, new BigDecimal("24.80"));  // long (BUY) placed first
        givenOrderComplete(orderId(0), 75, new BigDecimal("50.50"));  // short (SELL) second
        // net = 50.50 - 24.80 = 25.70 > 22.50 → no slippage

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.ACTIVE);
        assertThat(response.slippageAlert()).isFalse();
        assertThat(response.fills()).hasSize(2);
        assertThat(response.actualNetPremiumPerUnit()).isEqualByComparingTo(new BigDecimal("25.70"));
        verify(jdbc).update(contains("status = 'ACTIVE'"), anyString(), eq(TRADE_ID));
    }

    @Test
    void execute_actualNetBelowThreshold_activeWithSlippageAlert() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenPlacementAccepted();
        givenOrderComplete(orderId(1), 75, new BigDecimal("8.00"));
        givenOrderComplete(orderId(0), 75, new BigDecimal("30.00"));
        // net = 30 - 8 = 22.00 < 22.50 → slippage

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.ACTIVE);
        assertThat(response.slippageAlert()).isTrue();
        assertThat(response.slippageMessage()).isNotBlank();
    }

    @Test
    void execute_placesProtectiveLongLegBeforeShortLeg_uniqueTags() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenPlacementAccepted();
        givenOrderComplete(orderId(1), 75, new BigDecimal("24.80"));
        givenOrderComplete(orderId(0), 75, new BigDecimal("50.00"));

        service.execute(buildRequest());

        InOrder inOrder = inOrder(orderClient);
        // Protective long (BUY, index 1) placed FIRST with its unique per-leg tag
        inOrder.verify(orderClient).placeOrder(argThat(r ->
                "BUY".equals(r.transactionType())
                        && "LIMIT".equals(r.orderType())
                        && r.tag().equals(OrderTagBuilder.entryTag(TRADE_ID, 1))
                        && !r.slice()));
        // Short (SELL, index 0) placed SECOND with its own tag
        inOrder.verify(orderClient).placeOrder(argThat(r ->
                "SELL".equals(r.transactionType())
                        && r.tag().equals(OrderTagBuilder.entryTag(TRADE_ID, 0))));
    }

    // ── Oversized leg (no slicing) ─────────────────────────────────────────────

    @Test
    void execute_legExceedsFreezeLimit_rejectedBeforeAnyPlacement() {
        givenConfirmedTrade(EXPECTED_NET);
        // leg quantity 2000 > maxOrderQuantity 1755
        ExecuteTradeRequest oversized = new ExecuteTradeRequest(TRADE_ID, List.of(
                new LegOrderRequest(SHORT_KEY, OptionType.PE, 24500, LegAction.SELL, new BigDecimal("50.00"), 2000),
                new LegOrderRequest(LONG_KEY,  OptionType.PE, 24400, LegAction.BUY,  new BigDecimal("25.00"), 2000)));

        ExecuteTradeResponse response = service.execute(oversized);

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(response.rejectionReason()).containsIgnoringCase("exceeds");
        // Rejected before margin or placement — no Upstox calls at all
        verifyNoInteractions(orderClient);
    }

    // ── Trade not found ───────────────────────────────────────────────────────

    @Test
    void execute_tradeNotFoundInDb_returnsRejectedImmediately() {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any())).thenReturn(null);

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(response.rejectionReason()).containsIgnoringCase("not found");
        verifyNoInteractions(orderClient);
    }

    // ── Margin failures ───────────────────────────────────────────────────────

    @Test
    void execute_insufficientMargin_returnsRejected() {
        givenConfirmedTrade(EXPECTED_NET);
        when(orderClient.checkMargin(any())).thenReturn(
                new MarginCheckResponse("success", new MarginData(new BigDecimal("80000"), new BigDecimal("75000"))));
        when(orderClient.getAvailableFunds()).thenReturn(
                new FundsAndMarginResponse("success", new FundsAndMarginResponse.FundsData(
                        new FundsAndMarginResponse.SegmentFunds(new BigDecimal("50000"), BigDecimal.ZERO), null)));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(response.rejectionReason()).containsIgnoringCase("margin");
        verify(orderClient, never()).placeOrder(any());
    }

    @Test
    void execute_marginApiThrows_returnsRejected() {
        givenConfirmedTrade(EXPECTED_NET);
        when(orderClient.checkMargin(any()))
                .thenThrow(new UpstoxOrderException("503 Service Unavailable"));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(response.rejectionReason()).containsIgnoringCase("margin");
        verify(orderClient, never()).placeOrder(any());
    }

    // ── Deterministic rejection + rollback ─────────────────────────────────────

    @Test
    void execute_firstLegDeterministicallyRejected_noRollback() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        // deterministic 4xx (ambiguous=false) on the first (protective long) leg
        when(orderClient.placeOrder(any()))
                .thenThrow(new UpstoxOrderException("400 bad request", null, false));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        verify(orderClient, times(1)).placeOrder(any());   // short never attempted
        verify(orderClient, never()).cancelOrder(any());
    }

    @Test
    void execute_rejectStatusUpdateFails_firesCriticalAlert() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        // First (protective) leg deterministically rejected → rollback empty → rejected() invoked.
        when(orderClient.placeOrder(any()))
                .thenThrow(new UpstoxOrderException("400 bad request", null, false));
        // The REJECTED status write fails (e.g. DB down) — must NOT be swallowed silently.
        when(jdbc.update(contains("status = 'REJECTED'"), any(), any()))
                .thenThrow(new RuntimeException("ERROR: value too long / 22001"));

        ExecuteTradeResponse response = service.execute(buildRequest());

        // Caller still gets REJECTED (exchange is flat), but the DB↔state mismatch is escalated.
        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        verify(alertService).critical(eq(TRADE_ID), eq("status_update_failed"),
                contains("MANUAL INTERVENTION REQUIRED"));
    }

    @Test
    void execute_shortLegRejectedAfterLongFilled_rollsBackLong() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenPlacementRejectingEntry("SELL");   // long fills, short deterministically rejected
        givenOrderComplete(orderId(1), 75, new BigDecimal("24.80"));

        ArgumentCaptor<PlaceOrderV3Request> captor = ArgumentCaptor.forClass(PlaceOrderV3Request.class);
        ExecuteTradeResponse response = service.execute(buildRequest());
        verify(orderClient, atLeast(1)).placeOrder(captor.capture());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        // filled long reversed via MARKET (BUY → SELL) at full qty
        assertThat(captor.getAllValues()).anyMatch(r -> "MARKET".equals(r.orderType())
                && "SELL".equals(r.transactionType()) && LONG_KEY.equals(r.instrumentToken()) && r.quantity() == 75);
    }

    @Test
    void execute_rollbackOrderFails_firesCriticalAlert() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        when(orderClient.placeOrder(any())).thenAnswer(inv -> {
            PlaceOrderV3Request r = inv.getArgument(0);
            if ("MARKET".equals(r.orderType())) throw new UpstoxOrderException("rollback rejected");
            if ("SELL".equals(r.transactionType())) throw new UpstoxOrderException("short rejected", null, false);
            return placedOk("ORD_" + r.tag());
        });
        givenOrderComplete(orderId(1), 75, new BigDecimal("24.80"));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        // AlertService.critical routes to both notifications and critical_alerts (verified in AlertServiceTest)
        verify(alertService).critical(eq(TRADE_ID), eq("rollback_failed"), anyString());
    }

    @Test
    void execute_ironCondor_firstShortRejected_rollsBackBothLongs() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenPlacementRejectingEntry("SELL");   // both wings fill, first short rejected
        givenOrderComplete(orderId(1), 75, new BigDecimal("30.00"));  // long PE
        givenOrderComplete(orderId(3), 75, new BigDecimal("28.00"));  // long CE

        ArgumentCaptor<PlaceOrderV3Request> captor = ArgumentCaptor.forClass(PlaceOrderV3Request.class);
        ExecuteTradeResponse response = service.execute(buildIronCondorRequest());
        verify(orderClient, atLeast(1)).placeOrder(captor.capture());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        List<PlaceOrderV3Request> all = captor.getAllValues();
        assertThat(all).anyMatch(r -> "MARKET".equals(r.orderType())
                && "SELL".equals(r.transactionType()) && PE_LONG_KEY.equals(r.instrumentToken()));
        assertThat(all).anyMatch(r -> "MARKET".equals(r.orderType())
                && "SELL".equals(r.transactionType()) && CE_LONG_KEY.equals(r.instrumentToken()));
        // second short (CE) never placed as an entry — sequence stopped at the first failure
        assertThat(all).noneMatch(r -> "LIMIT".equals(r.orderType()) && CE_SHORT_KEY.equals(r.instrumentToken()));
    }

    // ── Fill timeout ──────────────────────────────────────────────────────────

    @Test
    void execute_firstLegTimesOut_cancelsAndReturnsRejectedNoRollback() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenPlacementAccepted();
        when(orderClient.getOrderStatus(orderId(1))).thenReturn(openStatus(orderId(1), "BUY", 75, 0));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        verify(orderClient).cancelOrder(orderId(1));
        verify(orderClient, times(1)).placeOrder(any());   // nothing filled → short never placed
    }

    @Test
    void execute_shortLegTimesOutAfterLongFilled_cancelsShortAndRollsBackLong() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenPlacementAccepted();
        givenOrderComplete(orderId(1), 75, new BigDecimal("24.80"));       // long fills
        when(orderClient.getOrderStatus(orderId(0))).thenReturn(openStatus(orderId(0), "SELL", 75, 0));

        ArgumentCaptor<PlaceOrderV3Request> captor = ArgumentCaptor.forClass(PlaceOrderV3Request.class);
        ExecuteTradeResponse response = service.execute(buildRequest());
        verify(orderClient, atLeast(1)).placeOrder(captor.capture());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        verify(orderClient).cancelOrder(orderId(0));
        assertThat(captor.getAllValues()).anyMatch(r -> "MARKET".equals(r.orderType())
                && "SELL".equals(r.transactionType()) && LONG_KEY.equals(r.instrumentToken()));
    }

    @Test
    void execute_shortLegPartiallyFills_rollsBackOnlyFilledQuantity() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        givenPlacementAccepted();
        givenOrderComplete(orderId(1), 75, new BigDecimal("24.80"));       // long fully fills
        // short ordered 75, only 30 fill; rests open → timeout → cancel
        when(orderClient.getOrderStatus(orderId(0))).thenReturn(openStatus(orderId(0), "SELL", 75, 30));

        ArgumentCaptor<PlaceOrderV3Request> captor = ArgumentCaptor.forClass(PlaceOrderV3Request.class);
        ExecuteTradeResponse response = service.execute(buildRequest());
        verify(orderClient, atLeast(1)).placeOrder(captor.capture());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        verify(orderClient).cancelOrder(orderId(0));
        List<PlaceOrderV3Request> markets = captor.getAllValues().stream()
                .filter(r -> "MARKET".equals(r.orderType())).toList();
        // the 30 partially-filled short units reversed (SELL → BUY), NOT the ordered 75
        assertThat(markets).anyMatch(r -> "BUY".equals(r.transactionType())
                && SHORT_KEY.equals(r.instrumentToken()) && r.quantity() == 30);
        assertThat(markets).anyMatch(r -> "SELL".equals(r.transactionType())
                && LONG_KEY.equals(r.instrumentToken()) && r.quantity() == 75);
        assertThat(markets).noneMatch(r -> SHORT_KEY.equals(r.instrumentToken()) && r.quantity() == 75);
    }

    // ── Debit spread correctness ───────────────────────────────────────────────

    @Test
    void execute_debitSpread_paidMoreThanExpected_slippageAlert() {
        givenConfirmedTrade(new BigDecimal("50.00"));
        givenSufficientMargin();
        givenPlacementAccepted();
        givenOrderComplete(orderId(1), 75, new BigDecimal("90.00"));  // long paid more
        givenOrderComplete(orderId(0), 75, new BigDecimal("28.00"));
        // net = 28 - 90 = -62; |62| > 50 × 1.10 = 55 → slippage (debit branch)

        ExecuteTradeResponse r = service.execute(buildDebitRequest());

        assertThat(r.executionStatus()).isEqualTo(TradeStatus.ACTIVE);
        assertThat(r.actualNetPremiumPerUnit()).isEqualByComparingTo(new BigDecimal("-62.00"));
        assertThat(r.slippageAlert()).isTrue();
    }

    @Test
    void execute_debitSpread_withinThreshold_noSlippageAlert() {
        givenConfirmedTrade(new BigDecimal("50.00"));
        givenSufficientMargin();
        givenPlacementAccepted();
        givenOrderComplete(orderId(1), 75, new BigDecimal("82.00"));
        givenOrderComplete(orderId(0), 75, new BigDecimal("30.00"));
        // net = 30 - 82 = -52; |52| < 55 → no slippage

        ExecuteTradeResponse r = service.execute(buildDebitRequest());

        assertThat(r.executionStatus()).isEqualTo(TradeStatus.ACTIVE);
        assertThat(r.actualNetPremiumPerUnit()).isEqualByComparingTo(new BigDecimal("-52.00"));
        assertThat(r.slippageAlert()).isFalse();
    }

    @Test
    void execute_debitSpread_shortRejectedAfterLongFilled_rollsBackLong() {
        givenConfirmedTrade(new BigDecimal("50.00"));
        givenSufficientMargin();
        givenPlacementRejectingEntry("SELL");
        givenOrderComplete(orderId(1), 75, new BigDecimal("80.00"));

        ArgumentCaptor<PlaceOrderV3Request> captor = ArgumentCaptor.forClass(PlaceOrderV3Request.class);
        ExecuteTradeResponse r = service.execute(buildDebitRequest());
        verify(orderClient, atLeast(1)).placeOrder(captor.capture());

        assertThat(r.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(captor.getAllValues()).anyMatch(o -> "MARKET".equals(o.orderType())
                && "SELL".equals(o.transactionType()) && CE_LONG_DEBIT_KEY.equals(o.instrumentToken()) && o.quantity() == 75);
    }

    // ── Ambiguous placement failure → reconcile by tag ─────────────────────────

    @Test
    void execute_placementThrowsAmbiguous_reconcilesByTagAndFlattens() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        // long entry accepted + fills; short entry throws AMBIGUOUS; MARKET reversals accepted
        when(orderClient.placeOrder(any())).thenAnswer(inv -> {
            PlaceOrderV3Request r = inv.getArgument(0);
            if ("LIMIT".equals(r.orderType()) && "SELL".equals(r.transactionType())) {
                throw new UpstoxOrderException("504 Gateway Timeout", null, true);
            }
            return placedOk("ORD_" + r.tag());
        });
        givenOrderComplete(orderId(1), 75, new BigDecimal("24.80"));

        String shortTag = OrderTagBuilder.entryTag(TRADE_ID, 0);
        // failed short leg IS on the exchange, resting open with 30 filled
        when(orderClient.getOrderDetailsByTag(shortTag)).thenReturn(new TaggedOrdersResponse("success", List.of(
                new TaggedOrdersResponse.TaggedOrder("SHORT_OID", shortTag, "open", "SELL",
                        SHORT_KEY, 75, 30, 45, new BigDecimal("50.00"), shortTag))));
        when(orderClient.getOrderStatus("SHORT_OID")).thenReturn(
                new OrderStatusResponse("success", new OrderStatusResponse.OrderData(
                        "SHORT_OID", "open", null, "SELL", 75, 30, 45, new BigDecimal("50.00"))));

        ArgumentCaptor<PlaceOrderV3Request> captor = ArgumentCaptor.forClass(PlaceOrderV3Request.class);
        ExecuteTradeResponse response = service.execute(buildRequest());
        verify(orderClient, atLeast(1)).placeOrder(captor.capture());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.REJECTED);  // driven flat
        verify(orderClient).cancelOrder("SHORT_OID");
        verify(criticalAlertService).record(eq(TRADE_ID), anyString(), any());
        List<PlaceOrderV3Request> markets = captor.getAllValues().stream()
                .filter(r -> "MARKET".equals(r.orderType())).toList();
        assertThat(markets).anyMatch(r -> "SELL".equals(r.transactionType())
                && LONG_KEY.equals(r.instrumentToken()) && r.quantity() == 75);   // long reversed
        assertThat(markets).anyMatch(r -> "BUY".equals(r.transactionType())
                && SHORT_KEY.equals(r.instrumentToken()) && r.quantity() == 30);  // short partial reversed
    }

    @Test
    void execute_placementThrowsAmbiguous_tagQueryEmpty_recordsNothingFound() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        // first (protective long) placement throws ambiguous → nothing filled yet
        when(orderClient.placeOrder(any())).thenThrow(new UpstoxOrderException("connection reset", null, true));
        when(orderClient.getOrderDetailsByTag(OrderTagBuilder.entryTag(TRADE_ID, 1)))
                .thenReturn(new TaggedOrdersResponse("success", List.of()));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.RECONCILE_REQUIRED);
        verify(criticalAlertService).record(eq(TRADE_ID), contains("nothing found"), any());
        verify(orderClient, never()).cancelOrder(any());
    }

    @Test
    void execute_placementThrowsAmbiguous_tagQueryFails_recordsReconcileRequired() {
        givenConfirmedTrade(EXPECTED_NET);
        givenSufficientMargin();
        when(orderClient.placeOrder(any())).thenThrow(new UpstoxOrderException("503", null, true));
        when(orderClient.getOrderDetailsByTag(OrderTagBuilder.entryTag(TRADE_ID, 1)))
                .thenThrow(new UpstoxOrderException("tag query 503"));

        ExecuteTradeResponse response = service.execute(buildRequest());

        assertThat(response.executionStatus()).isEqualTo(TradeStatus.RECONCILE_REQUIRED);
        verify(criticalAlertService).record(eq(TRADE_ID), contains("UNKNOWN"), any());
    }

    // ── Exit ────────────────────────────────────────────────────────────────

    @Test
    void exit_activeStatus_placesReverseMarketOrdersAndReturnsClosed() {
        givenCurrentTradeStatus("ACTIVE");
        givenPlacementAccepted();

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.status()).isEqualTo(TradeStatus.CLOSED);
        assertThat(response.failureReason()).isNull();
        assertThat(response.closedAt()).isNotNull();
        verify(orderClient, times(2)).placeOrder(any());   // one reverse per leg
    }

    @Test
    void exit_exitInProgressStatus_proceedsAndReturnsClosed() {
        givenCurrentTradeStatus("EXIT_IN_PROGRESS");
        givenPlacementAccepted();

        assertThat(service.exit(buildExitRequest()).status()).isEqualTo(TradeStatus.CLOSED);
    }

    @Test
    void exit_closedStatus_returnsEarlyNoOrderPlaced() {
        givenCurrentTradeStatus("CLOSED");

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

    @Test
    void exit_orderPlacementFails_setsExitFailedAndAlerts() {
        givenCurrentTradeStatus("ACTIVE");
        when(orderClient.placeOrder(any())).thenThrow(new UpstoxOrderException("connection refused"));

        ExitTradeResponse response = service.exit(buildExitRequest());

        assertThat(response.status()).isEqualTo(TradeStatus.EXIT_FAILED);
        assertThat(response.failureReason()).isNotBlank();
        // AlertService.critical routes to both notifications and critical_alerts (verified in AlertServiceTest)
        verify(alertService).critical(eq(TRADE_ID), anyString(), anyString());
    }

    @Test
    void exit_reversesLegActions_asMarketOrders() {
        givenCurrentTradeStatus("ACTIVE");
        ArgumentCaptor<PlaceOrderV3Request> captor = ArgumentCaptor.forClass(PlaceOrderV3Request.class);
        when(orderClient.placeOrder(captor.capture())).thenAnswer(inv -> placedOk("ORD_X"));

        service.exit(buildExitRequest());   // leg 0 = SELL, leg 1 = BUY

        List<PlaceOrderV3Request> placed = captor.getAllValues();
        assertThat(placed).hasSize(2);
        assertThat(placed).allMatch(r -> "MARKET".equals(r.orderType()));
        assertThat(placed).anyMatch(r -> "BUY".equals(r.transactionType()) && SHORT_KEY.equals(r.instrumentToken()));
        assertThat(placed).anyMatch(r -> "SELL".equals(r.transactionType()) && LONG_KEY.equals(r.instrumentToken()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void givenConfirmedTrade(BigDecimal netPremium) {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any())).thenReturn(netPremium);
    }

    private void givenSufficientMargin() {
        when(orderClient.checkMargin(any())).thenReturn(
                new MarginCheckResponse("success", new MarginData(new BigDecimal("80000"), new BigDecimal("75000"))));
        when(orderClient.getAvailableFunds()).thenReturn(
                new FundsAndMarginResponse("success", new FundsAndMarginResponse.FundsData(
                        new FundsAndMarginResponse.SegmentFunds(new BigDecimal("200000"), BigDecimal.ZERO), null)));
    }

    /** Every placeOrder returns success with order_id = "ORD_" + tag. */
    private void givenPlacementAccepted() {
        when(orderClient.placeOrder(any())).thenAnswer(inv -> {
            PlaceOrderV3Request r = inv.getArgument(0);
            return placedOk("ORD_" + r.tag());
        });
    }

    /** Deterministically rejects any ENTRY (LIMIT) leg of the given transaction type; accepts the rest. */
    private void givenPlacementRejectingEntry(String rejectTxType) {
        when(orderClient.placeOrder(any())).thenAnswer(inv -> {
            PlaceOrderV3Request r = inv.getArgument(0);
            if ("LIMIT".equals(r.orderType()) && rejectTxType.equals(r.transactionType())) {
                throw new UpstoxOrderException("rejected", null, false);
            }
            return placedOk("ORD_" + r.tag());
        });
    }

    private PlaceOrderV3Response placedOk(String orderId) {
        return new PlaceOrderV3Response("success", new PlaceOrderV3Response.Data(List.of(orderId)));
    }

    private void givenOrderComplete(String orderId, int qty, BigDecimal avgPrice) {
        OrderStatusResponse complete = new OrderStatusResponse("success",
                new OrderStatusResponse.OrderData(orderId, "complete", null, null, qty, qty, 0, avgPrice));
        when(orderClient.getOrderStatus(orderId)).thenReturn(complete);
    }

    private OrderStatusResponse openStatus(String orderId, String txType, int qty, int filled) {
        return new OrderStatusResponse("success",
                new OrderStatusResponse.OrderData(orderId, "open", null, txType, qty, filled, qty - filled, null));
    }

    /** Deterministic order_id assigned by the placedOk stub for an entry leg index. */
    private static String orderId(int legIndex) {
        return "ORD_" + OrderTagBuilder.entryTag(TRADE_ID, legIndex);
    }

    private ExecuteTradeRequest buildRequest() {
        return new ExecuteTradeRequest(TRADE_ID, List.of(
                new LegOrderRequest(SHORT_KEY, OptionType.PE, 24500, LegAction.SELL, new BigDecimal("50.00"), 75),
                new LegOrderRequest(LONG_KEY,  OptionType.PE, 24400, LegAction.BUY,  new BigDecimal("25.00"), 75)
        ));
    }

    private ExecuteTradeRequest buildDebitRequest() {
        return new ExecuteTradeRequest(TRADE_ID, List.of(
                new LegOrderRequest(CE_SHORT_DEBIT_KEY, OptionType.CE, 24600, LegAction.SELL, new BigDecimal("30.00"), 75),
                new LegOrderRequest(CE_LONG_DEBIT_KEY,  OptionType.CE, 24500, LegAction.BUY,  new BigDecimal("80.00"), 75)
        ));
    }

    private ExecuteTradeRequest buildIronCondorRequest() {
        return new ExecuteTradeRequest(TRADE_ID, List.of(
                new LegOrderRequest(PE_SHORT_KEY, OptionType.PE, 24000, LegAction.SELL, new BigDecimal("40.00"), 75),
                new LegOrderRequest(PE_LONG_KEY,  OptionType.PE, 23900, LegAction.BUY,  new BigDecimal("30.00"), 75),
                new LegOrderRequest(CE_SHORT_KEY, OptionType.CE, 25000, LegAction.SELL, new BigDecimal("38.00"), 75),
                new LegOrderRequest(CE_LONG_KEY,  OptionType.CE, 25100, LegAction.BUY,  new BigDecimal("28.00"), 75)
        ));
    }

    private void givenCurrentTradeStatus(String status) {
        when(jdbc.queryForObject(anyString(), eq(String.class), any())).thenReturn(status);
    }

    private ExitTradeRequest buildExitRequest() {
        return new ExitTradeRequest(TRADE_ID, "T3_EXIT_BREACH", List.of(
                new ExitTradeRequest.ExitLeg(SHORT_KEY, LegAction.SELL, 75),
                new ExitTradeRequest.ExitLeg(LONG_KEY,  LegAction.BUY, 75)
        ));
    }
}
