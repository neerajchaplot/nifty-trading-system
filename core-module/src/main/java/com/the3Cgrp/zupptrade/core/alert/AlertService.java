package com.the3Cgrp.zupptrade.core.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Writes user-facing alerts to the notifications table.
 *
 * All agents share this service — each has its own Spring context but all write to the
 * same PostgreSQL notifications table. The Orchestrator (or future UI) reads unacknowledged
 * rows and delivers them to the user.
 *
 * Never throws. Alert failures are logged and silently swallowed so a notifications DB
 * issue never cascades into a trading failure.
 */
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final JdbcTemplate jdbc;

    public AlertService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Write a CRITICAL alert — requires immediate user attention (exit failed, manual intervention). */
    public void critical(UUID tradeId, String event, String message) {
        write(tradeId, "CRITICAL", event, message);
    }

    /** Write a WARNING alert — elevated risk, user should review (slippage, T2 threshold hit). */
    public void warning(UUID tradeId, String event, String message) {
        write(tradeId, "WARNING", event, message);
    }

    /** Write an INFO alert — informational (position externally closed, VIX normalised). */
    public void info(UUID tradeId, String event, String message) {
        write(tradeId, "INFO", event, message);
    }

    private void write(UUID tradeId, String type, String event, String message) {
        try {
            jdbc.update(
                    "INSERT INTO notifications (trade_id, type, event, message) VALUES (?, ?, ?, ?)",
                    tradeId, type, event, message);
            log.info("alert.written type={} event={} tradeId={}", type, event, tradeId);
        } catch (Exception e) {
            log.error("alert.write.failed type={} event={} tradeId={} error={}",
                    type, event, tradeId, e.getMessage());
        }
    }
}
