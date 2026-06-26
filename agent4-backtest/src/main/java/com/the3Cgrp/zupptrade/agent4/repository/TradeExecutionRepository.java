package com.the3Cgrp.zupptrade.agent4.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads trade_executions directly — single-table query by trade_id.
 */
@Repository
public class TradeExecutionRepository {

    private static final String TABLE = "zupptrade_dev.trade_executions";

    private final JdbcTemplate jdbc;

    public TradeExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All execution records for one trade (ENTRY + EXIT), ordered chronologically. */
    public List<Map<String, Object>> findByTradeId(UUID tradeId) {
        String sql = "SELECT execution_type, broker_status, "
                + "requested_net_premium, actual_net_premium, "
                + "requested_lots, filled_lots, lot_size, slippage_amount, failure_reason, "
                + "placed_at, executed_at "
                + "FROM " + TABLE
                + " WHERE trade_id = ? ORDER BY placed_at ASC";
        return jdbc.queryForList(sql, tradeId);
    }
}
