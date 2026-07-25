package com.the3Cgrp.zupptrade.core.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CriticalAlertService — mocked JdbcTemplate, no DB.
 */
@ExtendWith(MockitoExtension.class)
class CriticalAlertServiceTest {

    @Mock private JdbcTemplate jdbc;

    @Test
    void record_insertsLiveRowWithTransparentJsonDetails() {
        CriticalAlertService svc = new CriticalAlertService(jdbc);
        UUID tradeId = UUID.randomUUID();

        UUID alertId = svc.record(tradeId, "ambiguous placement failure",
                Map.of("tag", "ZUPP_ABCD", "outcome", "reconciled to flat"));

        assertThat(alertId).isNotNull();
        verify(jdbc).update(
                contains("INSERT INTO critical_alerts"),
                any(UUID.class), eq(tradeId), eq("ambiguous placement failure"),
                argThat((String json) -> json.contains("ZUPP_ABCD") && json.contains("reconciled to flat")));
    }

    @Test
    void record_dbFailure_returnsNullAndNeverThrows() {
        CriticalAlertService svc = new CriticalAlertService(jdbc);
        when(jdbc.update(anyString(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        UUID alertId = svc.record(UUID.randomUUID(), "reason", Map.of("k", "v"));

        // Must be swallowed — a critical_alert write failure must never cascade into a trading failure.
        assertThat(alertId).isNull();
    }
}
