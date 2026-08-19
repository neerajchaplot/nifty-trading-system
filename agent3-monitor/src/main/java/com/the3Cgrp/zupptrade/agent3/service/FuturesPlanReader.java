package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Reads/updates {@code trade_future_ledger} via JdbcTemplate — Agent 3 does not own the table
 * (Agent 2 does), mirroring {@link TradeMonitorReader}'s no-JPA approach for the trades table.
 *
 * Only watchable rows (ARMED / BREAK_DETECTED) for today are returned; CONFIRMED/FILLED and the
 * terminal states drop out of the entry loop automatically, so a fired plan is never re-fired.
 */
@Service
public class FuturesPlanReader {

    private static final String SELECT_WATCHABLE = """
            SELECT id, plan_code, primary_arm, entry_price, status
            FROM trade_future_ledger
            WHERE trade_date = CURRENT_DATE
              AND status IN ('ARMED', 'BREAK_DETECTED')
              AND primary_arm IS NOT NULL
              AND entry_price IS NOT NULL
            """;

    private static final RowMapper<FuturesPlanRow> ROW_MAPPER = (rs, n) -> new FuturesPlanRow(
            rs.getObject("id", UUID.class),
            rs.getString("plan_code"),
            rs.getString("primary_arm"),
            rs.getBigDecimal("entry_price"),
            FuturePlanStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public FuturesPlanReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_FILLED_TODAY = """
            SELECT id, plan_code, primary_arm, entry_price, status
            FROM trade_future_ledger
            WHERE trade_date = CURRENT_DATE AND status = 'FILLED'
            """;

    public List<FuturesPlanRow> findWatchable() {
        return jdbc.query(SELECT_WATCHABLE, ROW_MAPPER);
    }

    /** EOD close scope: today's live (FILLED) plans awaiting realized-P&L settlement. */
    public List<FuturesPlanRow> findFilledToday() {
        return jdbc.query(SELECT_FILLED_TODAY, ROW_MAPPER);
    }

    /** Advance coarse status (e.g. ARMED → BREAK_DETECTED, → INVALIDATED, → EXPIRED). */
    public void updateStatus(UUID id, FuturePlanStatus status) {
        jdbc.update("UPDATE trade_future_ledger SET status = ?, updated_at = NOW() WHERE id = ?",
                status.name(), id);
    }

    /**
     * Entry trigger fired — advance ARMED/BREAK_DETECTED → CONFIRMED and stamp {@code activated_at}
     * BEFORE the Agent 5 handoff. Agent 5's GTT placement requires the plan to already be CONFIRMED
     * (its hard precondition), so this transition MUST be persisted first — otherwise the handoff is
     * rejected and the plan dies EXECUTION_FAILED. Agent 5 then advances CONFIRMED → FILLED (or its
     * own EXECUTION_FAILED). The WHERE guard scopes to the pre-handoff states so a concurrent re-run
     * can never overwrite a FILLED / failed plan. (schema V116: activated_at = "submitted to Agent 5")
     */
    public void markConfirmed(UUID id) {
        jdbc.update("""
                UPDATE trade_future_ledger
                SET status = 'CONFIRMED', activated_at = NOW(), updated_at = NOW()
                WHERE id = ? AND status IN ('ARMED', 'BREAK_DETECTED')
                """, id);
    }

    /**
     * Handoff to Agent 5 was unreachable — fail the plan fast (no retry). The user acts on the
     * critical alert. On a successful handoff Agent 5 itself advances the plan to FILLED, so Agent 3
     * never writes the status in that case.
     */
    public void markExecutionFailed(UUID id) {
        jdbc.update("""
                UPDATE trade_future_ledger
                SET status = 'EXECUTION_FAILED', activated_at = NOW(), updated_at = NOW()
                WHERE id = ?
                """, id);
    }

    /** Minimal projection needed to run the entry FSM for one plan. */
    public record FuturesPlanRow(
            UUID id,
            String planCode,
            String primaryArm,
            BigDecimal entryPrice,
            FuturePlanStatus status
    ) {}
}
