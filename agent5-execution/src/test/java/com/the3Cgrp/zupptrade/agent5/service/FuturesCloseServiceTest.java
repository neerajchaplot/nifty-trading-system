package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse.TaggedOrder;
import com.the3Cgrp.zupptrade.agent5.dto.FuturesCloseResponse;
import com.the3Cgrp.zupptrade.agent5.service.FuturesCloseService.PlanRow;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient.IntradayCandle;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FuturesCloseServiceTest {

    private JdbcTemplate jdbc;
    private UpstoxOrderClient orderClient;
    private AlertService alertService;
    private FuturesDayCandleProvider dayCandleProvider;
    private FuturesCloseService service;

    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        orderClient = mock(UpstoxOrderClient.class);
        alertService = mock(AlertService.class);
        dayCandleProvider = mock(FuturesDayCandleProvider.class);
        service = new FuturesCloseService(jdbc, orderClient, alertService, new FuturesCandleReplay(), dayCandleProvider);

        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(65); // lot size
    }

    private void stubPlan(PlanRow row) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(planId))).thenReturn(row);
    }

    // ── LIVE (by-tag) ──────────────────────────────────────────────────────────

    private PlanRow live(FuturePlanStatus status, String arm, String target, String stop,
                         String realized, String reason) {
        return new PlanRow(status, arm, new BigDecimal(target), new BigDecimal(stop),
                null, null, 0, realized == null ? null : new BigDecimal(realized), reason, "LIVE");
    }

    private TaggedOrder order(String txn, int qty, String avg) {
        return new TaggedOrder("O-" + txn, "corr", "complete", txn, "NSE_FO|63812",
                qty, qty, 0, new BigDecimal(avg), "ZUPP_TAG");
    }

    private void stubOrders(TaggedOrder... orders) {
        when(orderClient.getOrderDetailsByTag(anyString()))
                .thenReturn(new TaggedOrdersResponse("success", List.of(orders)));
    }

    @Test
    void live_longTargetHit_booksPositivePnl() {
        stubPlan(live(FuturePlanStatus.FILLED, "LONG_ROTATION", "24357", "24237", null, null));
        stubOrders(order("BUY", 65, "24280"), order("SELL", 65, "24357"));

        FuturesCloseResponse res = service.closePlan(planId);

        assertThat(res.status()).isEqualTo("CLOSED");
        assertThat(res.realizedPnl()).isEqualByComparingTo("5005.00");
        assertThat(res.closeReason()).isEqualTo("Target hit");
    }

    @Test
    void live_noRoundTrip_raisesCriticalAlert_notClosed() {
        stubPlan(live(FuturePlanStatus.FILLED, "LONG_ROTATION", "24357", "24237", null, null));
        stubOrders(order("BUY", 65, "24280")); // entry only, no exit

        FuturesCloseResponse res = service.closePlan(planId);

        assertThat(res.status()).isEqualTo("UNRESOLVED");
        verify(alertService).critical(eq(planId), eq("futures_eod_unresolved"), anyString());
    }

    @Test
    void alreadyClosed_isIdempotent_noBrokerCall() {
        stubPlan(live(FuturePlanStatus.CLOSED, "LONG_ROTATION", "24357", "24237", "5005.00", "Target hit"));

        FuturesCloseResponse res = service.closePlan(planId);

        assertThat(res.status()).isEqualTo("CLOSED");
        verify(orderClient, never()).getOrderDetailsByTag(any());
    }

    // ── SIMULATION (candle replay) ─────────────────────────────────────────────

    @Test
    void simulation_replaysCandles_booksPnl_noBrokerCall() {
        OffsetDateTime fill = OffsetDateTime.of(2026, 8, 3, 9, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        PlanRow sim = new PlanRow(FuturePlanStatus.FILLED, "LONG_ROTATION",
                new BigDecimal("24357"), new BigDecimal("24237"),   // target, stop
                new BigDecimal("24280"), fill, 1,                    // fill_price, activated_at, lots
                null, null, "SIMULATION");
        stubPlan(sim);
        when(dayCandleProvider.todayNifty5m()).thenReturn(List.of(
                candle(20, "24280", "24300", "24270", "24290"),
                candle(25, "24290", "24360", "24285", "24355"))); // high 24360 ≥ target

        FuturesCloseResponse res = service.closePlan(planId);

        assertThat(res.status()).isEqualTo("CLOSED");
        assertThat(res.realizedPnl()).isEqualByComparingTo("5005.00"); // (24357-24280)*65
        assertThat(res.closeReason()).isEqualTo("Target hit");
        verify(orderClient, never()).getOrderDetailsByTag(any());       // no broker call in sim
        verify(jdbc).update(contains("status = 'CLOSED'"), eq(new BigDecimal("5005.00")), eq("Target hit"), eq(planId));
    }

    private IntradayCandle candle(int minute, String o, String h, String l, String c) {
        OffsetDateTime t = OffsetDateTime.of(2026, 8, 3, 9, minute, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        return new IntradayCandle(t, new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), 0L);
    }
}
