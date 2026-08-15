package com.the3Cgrp.zupptrade.agent4.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only repository for analytics queries on v_agent4_trade_list.
 * All complex joins and JSONB extractions are in the view — this class
 * only adds WHERE clauses, ORDER BY, and pagination.
 */
@Repository
public class AnalyticsTradeRepository {

    private static final String SCHEMA = "zupptrade_dev";
    private static final String VIEW   = SCHEMA + ".v_agent4_trade_list";

    // Per-user read scoping (Phase 5). Null-tolerant like buildDateFilter: when the scope param is
    // NULL (admin — see OwnershipGuard.scopeProfileId), the clause is a no-op and all rows pass;
    // otherwise it restricts to that owner. NULL-owner (legacy) rows are excluded for non-admins,
    // matching OwnershipGuard.requireOwner(null). Bound as text and cast, as the driver does for dates.
    private static final String SCOPE_FILTER =
            " AND (CAST(? AS UUID) IS NULL OR user_profile_id = CAST(? AS UUID)) ";

    private final JdbcTemplate jdbc;

    public AnalyticsTradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Trade list (paginated) ────────────────────────────────

    public List<Map<String, Object>> findClosedTrades(LocalDate from, LocalDate to, UUID scope,
                                                       int offset, int limit) {
        String sql = "SELECT * FROM " + VIEW + " WHERE status = 'CLOSED' "
                + buildDateFilter("exit_date") + SCOPE_FILTER
                + " ORDER BY exit_date DESC LIMIT ? OFFSET ?";
        return jdbc.queryForList(sql, buildParams(from, to, scope, limit, offset));
    }

    public long countClosedTrades(LocalDate from, LocalDate to, UUID scope) {
        String sql = "SELECT COUNT(*) FROM " + VIEW + " WHERE status = 'CLOSED' "
                + buildDateFilter("exit_date") + SCOPE_FILTER;
        Long count = jdbc.queryForObject(sql, Long.class, buildParams(from, to, scope));
        return count == null ? 0 : count;
    }

    // ── Single trade audit ────────────────────────────────────

    /**
     * By-id fetch for the audit endpoint. Deliberately NOT scoped here: the row carries
     * {@code user_profile_id}, and the calling service applies {@code OwnershipGuard.requireOwner}
     * so a cross-user request gets a 403 (not a 404 that hides the row's existence).
     */
    public Optional<Map<String, Object>> findClosedTradeById(UUID tradeId) {
        String sql = "SELECT * FROM " + VIEW
                + " WHERE trade_id = ? AND status = 'CLOSED'";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tradeId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ── Portfolio aggregate metrics ───────────────────────────

    /**
     * Single-query aggregate over the view for the summary API.
     * Returns one row with all numeric KPIs; Java calculators derive breakdowns.
     */
    public Map<String, Object> getAggregateMetrics(LocalDate from, LocalDate to, UUID scope) {
        String sql = """
                SELECT
                    COUNT(*)                                                        AS total_trades,
                    COUNT(*) FILTER (WHERE actual_pnl > 0)                         AS win_count,
                    COUNT(*) FILTER (WHERE actual_pnl <= 0)                        AS loss_count,
                    COALESCE(SUM(actual_pnl), 0)                                   AS total_pnl,
                    COALESCE(SUM(max_profit_total), 0)                            AS total_max_profit,
                    MIN(actual_pnl)                                                AS max_loss,
                    COALESCE(AVG(roc_theoretical_pct), 0)                          AS avg_roc_theoretical,
                    COALESCE(SUM(adjustment_count), 0)                             AS total_adjustments
                FROM %s
                WHERE status = 'CLOSED' %s %s
                """.formatted(VIEW, buildDateFilter("exit_date"), SCOPE_FILTER);
        List<Map<String, Object>> rows = jdbc.queryForList(sql, buildParams(from, to, scope));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /**
     * Returns ordered list of (actual_pnl, roc_achieved_pct, roc_theoretical_pct,
     * real_expected_loss_total) for all closed trades — used by DrawdownCalculator
     * and RoC capture ratio.
     */
    public List<Map<String, Object>> findOrderedPnlList(LocalDate from, LocalDate to, UUID scope) {
        String sql = "SELECT actual_pnl, roc_achieved_pct, roc_theoretical_pct, "
                + "real_expected_loss_total FROM " + VIEW
                + " WHERE status = 'CLOSED' " + buildDateFilter("exit_date") + SCOPE_FILTER
                + " ORDER BY exit_date ASC";
        return jdbc.queryForList(sql, buildParams(from, to, scope));
    }

    /**
     * Win rate broken down by VIX regime and confidence label —
     * returned as flat list of rows; service groups them into Maps.
     */
    public List<Map<String, Object>> findClosedTradeGroupMetrics(LocalDate from, LocalDate to, UUID scope) {
        String sql = """
                SELECT
                    entry_vix_regime,
                    signal_confidence_label,
                    strategy,
                    COUNT(*)                                        AS trade_count,
                    COUNT(*) FILTER (WHERE actual_pnl > 0)         AS win_count,
                    adjustment_count,
                    exit_triggered_by
                FROM %s
                WHERE status = 'CLOSED' %s %s
                GROUP BY entry_vix_regime, signal_confidence_label, strategy,
                         adjustment_count, exit_triggered_by
                ORDER BY entry_vix_regime, signal_confidence_label
                """.formatted(VIEW, buildDateFilter("exit_date"), SCOPE_FILTER);
        return jdbc.queryForList(sql, buildParams(from, to, scope));
    }

    // ── Corrupted trades (separate line items, excluded from aggregations) ──

    /**
     * Returns all CORRUPTED_MANUALLY trades in the date range (keyed on closed_at).
     * Not paginated — there should be very few of these and the user needs to see all of them.
     */
    public List<Map<String, Object>> findCorruptedTrades(LocalDate from, LocalDate to, UUID scope) {
        String sql = "SELECT * FROM " + VIEW + " WHERE status = 'CORRUPTED_MANUALLY' "
                + buildDateFilter("exit_date") + SCOPE_FILTER
                + " ORDER BY exit_date DESC";
        return jdbc.queryForList(sql, buildParams(from, to, scope));
    }

    public long countCorruptedTrades(LocalDate from, LocalDate to, UUID scope) {
        String sql = "SELECT COUNT(*) FROM " + VIEW + " WHERE status = 'CORRUPTED_MANUALLY' "
                + buildDateFilter("exit_date") + SCOPE_FILTER;
        Long count = jdbc.queryForObject(sql, Long.class, buildParams(from, to, scope));
        return count == null ? 0 : count;
    }

    // ── Health ────────────────────────────────────────────────

    public long countAllClosedTrades() {
        String sql = "SELECT COUNT(*) FROM " + VIEW + " WHERE status = 'CLOSED'";
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0 : count;
    }

    public Optional<Timestamp> findLastClosedTradeTimestamp() {
        String sql = "SELECT MAX(exit_date) FROM " + VIEW + " WHERE status = 'CLOSED'";
        Timestamp ts = jdbc.queryForObject(sql, Timestamp.class);
        return Optional.ofNullable(ts);
    }

    // ── Helpers ───────────────────────────────────────────────

    private String buildDateFilter(String column) {
        return " AND (CAST(? AS DATE) IS NULL OR " + column + " >= CAST(? AS DATE)) "
             + " AND (CAST(? AS DATE) IS NULL OR " + column + " <= CAST(? AS DATE) + INTERVAL '1 day') ";
    }

    /**
     * Builds the parameter array for queries that use buildDateFilter + SCOPE_FILTER, in that SQL
     * order: 4 date slots (from×2, to×2), then 2 scope slots (scope×2), then any trailing varargs
     * (e.g. LIMIT, OFFSET). The scope is bound as text so {@code CAST(? AS UUID)} accepts both a
     * value and NULL (admin — unscoped).
     */
    private Object[] buildParams(LocalDate from, LocalDate to, UUID scope, Object... trailing) {
        Object fromVal  = from  != null ? java.sql.Date.valueOf(from) : null;
        Object toVal    = to    != null ? java.sql.Date.valueOf(to)   : null;
        Object scopeVal = scope != null ? scope.toString()           : null;
        // 4 slots for date filter (from×2, to×2) + 2 slots for scope filter (scope×2)
        Object[] head = new Object[]{fromVal, fromVal, toVal, toVal, scopeVal, scopeVal};
        Object[] all = new Object[head.length + trailing.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(trailing, 0, all, head.length, trailing.length);
        return all;
    }
}
