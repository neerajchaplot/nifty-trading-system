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

    private final JdbcTemplate jdbc;

    public AnalyticsTradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Trade list (paginated) ────────────────────────────────

    public List<Map<String, Object>> findClosedTrades(LocalDate from, LocalDate to,
                                                       int offset, int limit) {
        String sql = "SELECT * FROM " + VIEW + " WHERE status = 'CLOSED' "
                + buildDateFilter("exit_date")
                + " ORDER BY exit_date DESC LIMIT ? OFFSET ?";
        return jdbc.queryForList(sql, buildDateParams(from, to, limit, offset));
    }

    public long countClosedTrades(LocalDate from, LocalDate to) {
        String sql = "SELECT COUNT(*) FROM " + VIEW + " WHERE status = 'CLOSED' "
                + buildDateFilter("exit_date");
        Long count = jdbc.queryForObject(sql, Long.class, buildDateParams(from, to));
        return count == null ? 0 : count;
    }

    // ── Single trade audit ────────────────────────────────────

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
    public Map<String, Object> getAggregateMetrics(LocalDate from, LocalDate to) {
        String sql = """
                SELECT
                    COUNT(*)                                                        AS total_trades,
                    COUNT(*) FILTER (WHERE actual_pnl > 0)                         AS win_count,
                    COUNT(*) FILTER (WHERE actual_pnl <= 0)                        AS loss_count,
                    COALESCE(SUM(actual_pnl), 0)                                   AS total_pnl,
                    MIN(actual_pnl)                                                AS max_loss,
                    COALESCE(AVG(roc_theoretical_pct), 0)                          AS avg_roc_theoretical,
                    COALESCE(SUM(adjustment_count), 0)                             AS total_adjustments
                FROM %s
                WHERE status = 'CLOSED' %s
                """.formatted(VIEW, buildDateFilter("exit_date"));
        List<Map<String, Object>> rows = jdbc.queryForList(sql, buildDateParams(from, to));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /**
     * Returns ordered list of (actual_pnl, roc_achieved_pct, roc_theoretical_pct,
     * real_expected_loss_total) for all closed trades — used by DrawdownCalculator
     * and RoC capture ratio.
     */
    public List<Map<String, Object>> findOrderedPnlList(LocalDate from, LocalDate to) {
        String sql = "SELECT actual_pnl, roc_achieved_pct, roc_theoretical_pct, "
                + "real_expected_loss_total FROM " + VIEW
                + " WHERE status = 'CLOSED' " + buildDateFilter("exit_date")
                + " ORDER BY exit_date ASC";
        return jdbc.queryForList(sql, buildDateParams(from, to));
    }

    /**
     * Win rate broken down by VIX regime and confidence label —
     * returned as flat list of rows; service groups them into Maps.
     */
    public List<Map<String, Object>> findClosedTradeGroupMetrics(LocalDate from, LocalDate to) {
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
                WHERE status = 'CLOSED' %s
                GROUP BY entry_vix_regime, signal_confidence_label, strategy,
                         adjustment_count, exit_triggered_by
                ORDER BY entry_vix_regime, signal_confidence_label
                """.formatted(VIEW, buildDateFilter("exit_date"));
        return jdbc.queryForList(sql, buildDateParams(from, to));
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
     * Builds the parameter array for queries that use buildDateFilter.
     * Trailing varargs are appended after the date params (e.g., LIMIT, OFFSET).
     */
    private Object[] buildDateParams(LocalDate from, LocalDate to, Object... trailing) {
        Object fromVal = from != null ? java.sql.Date.valueOf(from) : null;
        Object toVal   = to   != null ? java.sql.Date.valueOf(to)   : null;
        // 4 slots for date filter (from×2, to×2)
        Object[] dateArgs = new Object[]{fromVal, fromVal, toVal, toVal};
        Object[] all = new Object[dateArgs.length + trailing.length];
        System.arraycopy(dateArgs, 0, all, 0, dateArgs.length);
        System.arraycopy(trailing, 0, all, dateArgs.length, trailing.length);
        return all;
    }
}
