package com.the3Cgrp.zupptrade.agent4.repository;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.FuturesPnlRowDto;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads CLOSED futures plans from {@code trade_future_ledger} for the Agent 4 futures P&L filter.
 * JdbcTemplate + direct table read (no view), matching {@link AnalyticsTradeRepository}'s approach.
 */
@Repository
public class FuturesAnalyticsRepository {

    private static final RowMapper<FuturesPnlRowDto> ROW_MAPPER = (rs, n) -> {
        String arm = rs.getString("primary_arm");
        String direction = arm != null ? FutureArmType.valueOf(arm).direction().name() : null;
        return new FuturesPnlRowDto(
                rs.getString("plan_code"),
                rs.getObject("trade_date", LocalDate.class),
                arm,
                direction,
                rs.getBigDecimal("entry_price"),
                rs.getBigDecimal("stop_price"),
                rs.getBigDecimal("target_price"),
                rs.getBigDecimal("realized_pnl"),
                rs.getString("close_reason"),
                rs.getString("bias"),
                rs.getString("confidence_label"));
    };

    private final JdbcTemplate jdbc;

    public FuturesAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** CLOSED futures trades, newest first, optionally bounded by trade_date. */
    public List<FuturesPnlRowDto> findClosed(LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                SELECT plan_code, trade_date, primary_arm, entry_price, stop_price, target_price,
                       realized_pnl, close_reason, bias, confidence_label
                FROM trade_future_ledger
                WHERE status = 'CLOSED'
                """);
        List<Object> args = new ArrayList<>();
        if (from != null) { sql.append(" AND trade_date >= ?"); args.add(from); }
        if (to != null)   { sql.append(" AND trade_date <= ?"); args.add(to); }
        sql.append(" ORDER BY trade_date DESC, created_at DESC");
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }
}
