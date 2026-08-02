package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse.TaggedOrder;
import com.the3Cgrp.zupptrade.agent5.dto.FuturesCloseResponse;
import com.the3Cgrp.zupptrade.agent5.service.FuturesCloseService.PlanRow;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FuturesCloseServiceTest {

    private JdbcTemplate jdbc;
    private UpstoxOrderClient orderClient;
    private AlertService alertService;
    private FuturesCloseService service;

    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        orderClient = mock(UpstoxOrderClient.class);
        alertService = mock(AlertService.class);
        service = new FuturesCloseService(jdbc, orderClient, alertService);
    }

    private void stubPlan(PlanRow row) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(planId))).thenReturn(row);
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
    void longTargetHit_booksPositivePnl_closed() {
        stubPlan(new PlanRow(FuturePlanStatus.FILLED, "LONG_ROTATION",
                new BigDecimal("24357"), new BigDecimal("24237"), null, null));
        stubOrders(order("BUY", 65, "24280"), order("SELL", 65, "24357"));

        FuturesCloseResponse res = service.closePlan(planId);

        assertThat(res.status()).isEqualTo("CLOSED");
        assertThat(res.realizedPnl()).isEqualByComparingTo("5005.00"); // (24357-24280)*65
        assertThat(res.closeReason()).isEqualTo("Target hit");
        verify(jdbc).update(contains("status = 'CLOSED'"),
                eq(new BigDecimal("5005.00")), eq("Target hit"), eq(planId));
        verify(alertService).info(eq(planId), eq("futures_closed"), anyString());
    }

    @Test
    void shortTargetHit_booksPositivePnl() {
        stubPlan(new PlanRow(FuturePlanStatus.FILLED, "SHORT_ROTATION",
                new BigDecimal("24277"), new BigDecimal("24397"), null, null));
        stubOrders(order("SELL", 65, "24357"), order("BUY", 65, "24277")); // sell entry, buy exit

        FuturesCloseResponse res = service.closePlan(planId);

        assertThat(res.status()).isEqualTo("CLOSED");
        assertThat(res.realizedPnl()).isEqualByComparingTo("5200.00"); // (24357-24277)*65
        assertThat(res.closeReason()).isEqualTo("Target hit");
    }

    @Test
    void noRoundTripUnderTag_raisesCriticalAlert_notClosed() {
        stubPlan(new PlanRow(FuturePlanStatus.FILLED, "LONG_ROTATION",
                new BigDecimal("24357"), new BigDecimal("24237"), null, null));
        stubOrders(order("BUY", 65, "24280")); // entry only, no exit

        FuturesCloseResponse res = service.closePlan(planId);

        assertThat(res.status()).isEqualTo("UNRESOLVED");
        verify(alertService).critical(eq(planId), eq("futures_eod_unresolved"), anyString());
        verify(jdbc, never()).update(contains("CLOSED"), any(), any(), any());
    }

    @Test
    void alreadyClosed_isIdempotent_noBrokerCall() {
        stubPlan(new PlanRow(FuturePlanStatus.CLOSED, "LONG_ROTATION",
                new BigDecimal("24357"), new BigDecimal("24237"), new BigDecimal("5005.00"), "Target hit"));

        FuturesCloseResponse res = service.closePlan(planId);

        assertThat(res.status()).isEqualTo("CLOSED");
        assertThat(res.realizedPnl()).isEqualByComparingTo("5005.00");
        verify(orderClient, never()).getOrderDetailsByTag(any());
    }
}
