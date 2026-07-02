package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads minimal trade data from the trades table without a JPA entity.
 * Agent 3 does not own the trades table — using JdbcTemplate avoids entity duplication.
 */
@Service
public class TradeMonitorReader {

    private static final String SELECT_BY_ID =
            "SELECT id, user_profile_id, status, monitor_config, entry_fills, market_context, trade_code, expiry_date " +
            "FROM trades WHERE id = ?";

    private static final String SELECT_ALL_ACTIVE =
            "SELECT id, user_profile_id, status, monitor_config, entry_fills, market_context, trade_code, expiry_date " +
            "FROM trades WHERE status IN ('ACTIVE', 'EXIT_FAILED')";

    // Trades still ACTIVE past their expiry date with P&L not yet computed.
    // These are candidates for the morning expiry sweep in ExpiryPnlService.
    private static final String SELECT_EXPIRED_ACTIVE =
            "SELECT id, user_profile_id, status, monitor_config, entry_fills, market_context, trade_code, expiry_date " +
            "FROM trades WHERE status = 'ACTIVE' AND expiry_date < CURRENT_DATE AND actual_pnl IS NULL";

    private static final RowMapper<TradeMonitorData> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final JdbcTemplate jdbc;

    public TradeMonitorReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the trade data needed for monitoring a single trade.
     * Returns empty if trade not found.
     */
    public Optional<TradeMonitorData> findById(UUID tradeId) {
        try {
            TradeMonitorData data = jdbc.queryForObject(SELECT_BY_ID, ROW_MAPPER, tradeId);
            return Optional.ofNullable(data);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns all trades currently in ACTIVE status.
     * Called by the monitoring scheduler at the start of each 5-minute cycle.
     */
    public List<TradeMonitorData> findAllActive() {
        return jdbc.query(SELECT_ALL_ACTIVE, ROW_MAPPER);
    }

    /**
     * Returns ACTIVE trades where expiry_date < today and actual_pnl is null.
     * Called by the morning expiry sweep (ExpiryPnlService) to compute settled P&L.
     */
    public List<TradeMonitorData> findExpiredActiveBeforeToday() {
        return jdbc.query(SELECT_EXPIRED_ACTIVE, ROW_MAPPER);
    }

    private static TradeMonitorData mapRow(ResultSet rs) throws SQLException {
        String profileIdStr = rs.getString("user_profile_id");
        return new TradeMonitorData(
                UUID.fromString(rs.getString("id")),
                profileIdStr != null ? UUID.fromString(profileIdStr) : null,
                TradeStatus.valueOf(rs.getString("status")),
                rs.getString("monitor_config"),
                rs.getString("market_context"),
                rs.getString("trade_code"),
                rs.getObject("expiry_date", LocalDate.class),
                rs.getString("entry_fills")
        );
    }
}
