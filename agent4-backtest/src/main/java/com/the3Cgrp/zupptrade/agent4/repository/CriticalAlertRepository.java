package com.the3Cgrp.zupptrade.agent4.repository;

import com.the3Cgrp.zupptrade.agent4.config.AnalyticsConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repository for the critical_alerts table (V112).
 *
 * <p>Agent 4 is otherwise read-only; the single {@link #acknowledge(UUID)} write is a
 * user-driven state flip (LIVE → ACKNOWLEDGED) and is intentionally the only mutating query
 * in this module. All access is via native SQL + JdbcTemplate to match the module's style.
 */
@Repository
public class CriticalAlertRepository {

    private final JdbcTemplate jdbc;
    private final String table;

    public CriticalAlertRepository(JdbcTemplate jdbc, AnalyticsConfig config) {
        this.jdbc  = jdbc;
        this.table = config.getDbSchema() + ".critical_alerts";
    }

    /** All LIVE (unacknowledged) alerts, newest first. */
    public List<Map<String, Object>> findLive() {
        String sql = "SELECT alert_id, trade_id, alert_reason, trade_details, status, "
                + "created_at, acknowledged_at FROM " + table
                + " WHERE status = 'LIVE' ORDER BY created_at DESC";
        return jdbc.queryForList(sql);
    }

    /**
     * Flips a LIVE alert to ACKNOWLEDGED. Guarded on {@code status = 'LIVE'} so a second
     * acknowledge is a no-op.
     *
     * @return rows affected — 1 when acknowledged, 0 when not found or already acknowledged.
     */
    public int acknowledge(UUID alertId) {
        String sql = "UPDATE " + table
                + " SET status = 'ACKNOWLEDGED', acknowledged_at = NOW() "
                + "WHERE alert_id = ? AND status = 'LIVE'";
        return jdbc.update(sql, alertId);
    }
}
