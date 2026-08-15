package com.the3Cgrp.zupptrade.agent4.repository;

import com.the3Cgrp.zupptrade.agent4.config.AnalyticsConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the critical_alerts table (V112).
 *
 * <p>Agent 4 is otherwise read-only; the single {@link #acknowledge(UUID)} write is a
 * user-driven state flip (LIVE → ACKNOWLEDGED) and is intentionally the only mutating query
 * in this module. All access is via native SQL + JdbcTemplate to match the module's style.
 *
 * <p>Per-user scoping (Phase 5): an alert belongs to whoever owns its {@code trade_id}, so both
 * reads join {@code trades} and filter on {@code trades.user_profile_id}.
 */
@Repository
public class CriticalAlertRepository {

    private final JdbcTemplate jdbc;
    private final String table;
    private final String tradesTable;

    public CriticalAlertRepository(JdbcTemplate jdbc, AnalyticsConfig config) {
        this.jdbc        = jdbc;
        this.table       = config.getDbSchema() + ".critical_alerts";
        this.tradesTable = config.getDbSchema() + ".trades";
    }

    /**
     * LIVE (unacknowledged) alerts, newest first, scoped to the caller. Null-tolerant like the
     * analytics queries: {@code scope == null} (admin) returns every LIVE alert; otherwise only
     * alerts whose trade is owned by that user. Alerts with no/foreign trade owner are excluded
     * for non-admins.
     */
    public List<Map<String, Object>> findLive(UUID scope) {
        String sql = "SELECT ca.alert_id, ca.trade_id, ca.alert_reason, ca.trade_details, ca.status, "
                + "ca.created_at, ca.acknowledged_at "
                + "FROM " + table + " ca "
                + "LEFT JOIN " + tradesTable + " t ON t.id = ca.trade_id "
                + "WHERE ca.status = 'LIVE' "
                + "  AND (CAST(? AS UUID) IS NULL OR t.user_profile_id = CAST(? AS UUID)) "
                + "ORDER BY ca.created_at DESC";
        Object scopeVal = scope != null ? scope.toString() : null;
        return jdbc.queryForList(sql, scopeVal, scopeVal);
    }

    /** Ownership of an alert. {@code ownerProfileId} may be null (trade missing/unattributed). */
    public record AlertOwnership(UUID ownerProfileId) {}

    /**
     * The profile that owns an alert (via its trade), for the acknowledge ownership check.
     * Returns empty ONLY when the alert row does not exist (caller maps to 404). A present result
     * with a null {@code ownerProfileId} means the alert exists but its trade is missing or
     * unattributed — visible to admins only.
     */
    public Optional<AlertOwnership> findAlertOwner(UUID alertId) {
        String sql = "SELECT t.user_profile_id AS owner "
                + "FROM " + table + " ca "
                + "LEFT JOIN " + tradesTable + " t ON t.id = ca.trade_id "
                + "WHERE ca.alert_id = ?";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, alertId);
        if (rows.isEmpty()) {
            return Optional.empty();                 // alert absent → 404
        }
        return Optional.of(new AlertOwnership((UUID) rows.get(0).get("owner")));
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
