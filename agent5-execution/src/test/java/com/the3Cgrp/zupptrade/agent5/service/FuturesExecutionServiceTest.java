package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxGttClient;
import com.the3Cgrp.zupptrade.agent5.client.response.PlaceGttResponse;
import com.the3Cgrp.zupptrade.agent5.dto.FuturesGttResponse;
import com.the3Cgrp.zupptrade.agent5.service.FuturesExecutionService.PlanRow;
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

/**
 * FuturesExecutionService — GTT placement + critical-alert behaviour, mocked JDBC/client/margin/alerts.
 */
class FuturesExecutionServiceTest {

    private JdbcTemplate jdbc;
    private UpstoxGttClient gttClient;
    private MarginCheckService marginCheckService;
    private AlertService alertService;
    private FuturesExecutionService service;

    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        gttClient = mock(UpstoxGttClient.class);
        marginCheckService = mock(MarginCheckService.class);
        alertService = mock(AlertService.class);
        service = new FuturesExecutionService(jdbc, gttClient, marginCheckService, alertService);

        // lot size lookup
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(65);
    }

    private void stubPlan(PlanRow row) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(planId))).thenReturn(row);
    }

    private PlanRow confirmedLong(String instrument) {
        return new PlanRow(FuturePlanStatus.CONFIRMED, "LONG_ROTATION",
                new BigDecimal("24280"), new BigDecimal("24237"), new BigDecimal("24357"),
                instrument, null, 1);
    }

    private void stubFunds(String available) {
        when(marginCheckService.utilization()).thenReturn(new MarginCheckService.MarginUtilizationDto(
                BigDecimal.ZERO, new BigDecimal(available), new BigDecimal(available), BigDecimal.ZERO));
    }

    @Test
    void success_placesGtt_marksFilled_infoAlert() {
        stubPlan(confirmedLong("NSE_FO|63812"));
        stubFunds("10000000");
        when(gttClient.placeGtt(any())).thenReturn(
                new PlaceGttResponse("success", new PlaceGttResponse.Data(List.of("GTT-1"))));

        FuturesGttResponse res = service.placeGtt(planId);

        assertThat(res.status()).isEqualTo("FILLED");
        assertThat(res.gttOrderId()).isEqualTo("GTT-1");
        verify(jdbc).update(contains("status = 'FILLED'"), eq("GTT-1"), any(BigDecimal.class), eq(planId));
        verify(alertService).info(eq(planId), eq("futures_gtt_placed"), anyString());
        verify(alertService, never()).critical(any(), any(), any());
    }

    @Test
    void insufficientMargin_criticalAlert_executionFailed_noPlacement() {
        stubPlan(confirmedLong("NSE_FO|63812"));
        stubFunds("1000"); // far below required

        FuturesGttResponse res = service.placeGtt(planId);

        assertThat(res.status()).isEqualTo("EXECUTION_FAILED");
        verify(alertService).critical(eq(planId), eq("futures_margin_insufficient"), anyString());
        verify(jdbc).update(contains("EXECUTION_FAILED"), eq(planId));
        verify(gttClient, never()).placeGtt(any());
    }

    @Test
    void gttApiError_criticalAlert_executionFailed() {
        stubPlan(confirmedLong("NSE_FO|63812"));
        stubFunds("10000000");
        when(gttClient.placeGtt(any())).thenThrow(new RuntimeException("boom"));

        FuturesGttResponse res = service.placeGtt(planId);

        assertThat(res.status()).isEqualTo("EXECUTION_FAILED");
        verify(alertService).critical(eq(planId), eq("futures_gtt_failed"), anyString());
        verify(jdbc).update(contains("EXECUTION_FAILED"), eq(planId));
    }

    @Test
    void missingInstrument_criticalAlert_noPlacement() {
        stubPlan(confirmedLong(null));

        FuturesGttResponse res = service.placeGtt(planId);

        assertThat(res.status()).isEqualTo("EXECUTION_FAILED");
        verify(alertService).critical(eq(planId), eq("futures_instrument_unresolved"), anyString());
        verify(gttClient, never()).placeGtt(any());
    }

    @Test
    void alreadyFilled_isIdempotent_noPlacement() {
        stubPlan(new PlanRow(FuturePlanStatus.FILLED, "LONG_ROTATION",
                new BigDecimal("24280"), new BigDecimal("24237"), new BigDecimal("24357"),
                "NSE_FO|63812", "GTT-old", 1));

        FuturesGttResponse res = service.placeGtt(planId);

        assertThat(res.status()).isEqualTo("FILLED");
        assertThat(res.gttOrderId()).isEqualTo("GTT-old");
        verify(gttClient, never()).placeGtt(any());
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }
}
