package com.the3Cgrp.zupptrade.agent4.repository;

import com.the3Cgrp.zupptrade.agent4.config.AnalyticsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CriticalAlertRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CriticalAlertRepository repo = new CriticalAlertRepository(jdbc, config());

    private static AnalyticsConfig config() {
        AnalyticsConfig c = new AnalyticsConfig();
        c.setDbSchema("zupptrade_dev");
        return c;
    }

    @Test
    void findLive_queriesLiveRowsNewestFirstFromSchemaQualifiedTable() {
        when(jdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("alert_id", UUID.randomUUID())));

        List<Map<String, Object>> rows = repo.findLive();

        assertThat(rows).hasSize(1);
        verify(jdbc).queryForList(contains("zupptrade_dev.critical_alerts"));
        verify(jdbc).queryForList(contains("status = 'LIVE'"));
        verify(jdbc).queryForList(contains("ORDER BY created_at DESC"));
    }

    @Test
    void acknowledge_updatesGuardedOnLiveStatus_returnsRowsAffected() {
        UUID alertId = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(alertId))).thenReturn(1);

        int affected = repo.acknowledge(alertId);

        assertThat(affected).isEqualTo(1);
        verify(jdbc).update(contains("SET status = 'ACKNOWLEDGED'"), eq(alertId));
        verify(jdbc).update(contains("status = 'LIVE'"), any(UUID.class));
    }

    @Test
    void acknowledge_alreadyAcknowledgedOrMissing_returnsZero() {
        UUID alertId = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(alertId))).thenReturn(0);

        assertThat(repo.acknowledge(alertId)).isZero();
    }
}
