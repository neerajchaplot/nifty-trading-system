package com.the3Cgrp.zupptrade.agent4.repository;

import com.the3Cgrp.zupptrade.agent4.config.AnalyticsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentMatchers;

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

    /** HashMap-backed single entry — Map.of rejects null values, which we need for a null owner. */
    private static Map<String, Object> mapWith(String key, Object value) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    void findLive_scopedQueryJoinsTradesAndFiltersOnOwner() {
        UUID scope = UUID.randomUUID();
        // <Object> witnesses force the queryForList(String, Object...) overload — otherwise the bare
        // matcher's inferred type collides with queryForList(String, Class, Object...).
        when(jdbc.queryForList(anyString(), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(Map.of("alert_id", UUID.randomUUID())));

        List<Map<String, Object>> rows = repo.findLive(scope);

        assertThat(rows).hasSize(1);
        // Scope bound twice as text for CAST(? AS UUID)
        verify(jdbc).queryForList(contains("zupptrade_dev.critical_alerts"),
                eq(scope.toString()), eq(scope.toString()));
        verify(jdbc).queryForList(contains("LEFT JOIN zupptrade_dev.trades"),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any());
        verify(jdbc).queryForList(contains("t.user_profile_id = CAST(? AS UUID)"),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any());
        verify(jdbc).queryForList(contains("ca.status = 'LIVE'"),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any());
        verify(jdbc).queryForList(contains("ORDER BY ca.created_at DESC"),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any());
    }

    @Test
    void findLive_adminScopeNull_boundAsNull() {
        when(jdbc.queryForList(anyString(), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of());

        repo.findLive(null);

        // null scope → CAST(NULL AS UUID) IS NULL → all rows (admin)
        verify(jdbc).queryForList(anyString(), ArgumentMatchers.<Object>isNull(), ArgumentMatchers.<Object>isNull());
    }

    @Test
    void findAlertOwner_absentAlert_returnsEmpty() {
        UUID alertId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(alertId))).thenReturn(List.of());

        assertThat(repo.findAlertOwner(alertId)).isEmpty();
    }

    @Test
    void findAlertOwner_presentAlert_returnsOwnershipEvenWhenOwnerNull() {
        UUID alertId = UUID.randomUUID();
        UUID owner   = UUID.randomUUID();

        // Owner present
        when(jdbc.queryForList(anyString(), eq(alertId)))
                .thenReturn(List.of(mapWith("owner", owner)));
        Optional<CriticalAlertRepository.AlertOwnership> withOwner = repo.findAlertOwner(alertId);
        assertThat(withOwner).isPresent();
        assertThat(withOwner.get().ownerProfileId()).isEqualTo(owner);

        // Alert present but unattributed (null owner) — must still be present, not empty
        when(jdbc.queryForList(anyString(), eq(alertId)))
                .thenReturn(List.of(mapWith("owner", null)));
        Optional<CriticalAlertRepository.AlertOwnership> nullOwner = repo.findAlertOwner(alertId);
        assertThat(nullOwner).isPresent();
        assertThat(nullOwner.get().ownerProfileId()).isNull();
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
