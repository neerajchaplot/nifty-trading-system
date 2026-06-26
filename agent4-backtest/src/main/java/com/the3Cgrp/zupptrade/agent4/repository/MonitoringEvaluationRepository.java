package com.the3Cgrp.zupptrade.agent4.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads monitoring_evaluations directly — no view needed, it is a single-table
 * query filtered by trade_id.
 */
@Repository
public class MonitoringEvaluationRepository {

    private static final String TABLE = "zupptrade_dev.monitoring_evaluations";

    private final JdbcTemplate jdbc;

    public MonitoringEvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Full evaluation timeline for one trade, oldest first. */
    public List<Map<String, Object>> findByTradeId(UUID tradeId) {
        String sql = "SELECT evaluated_at, action, reason, threshold_hit, "
                + "spot_price, vix_level, current_pop, mark_to_market_pnl, "
                + "short_leg_ltp, long_leg_ltp "
                + "FROM " + TABLE
                + " WHERE trade_id = ? ORDER BY evaluated_at ASC";
        return jdbc.queryForList(sql, tradeId);
    }
}
