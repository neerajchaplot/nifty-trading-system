package com.the3Cgrp.zupptrade.core.alert;

import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Records CRITICAL, user-actionable alerts to the critical_alerts table.
 *
 * Distinct from {@link AlertService} (which writes routine notifications): a critical_alert
 * represents a situation the system could NOT resolve automatically and that requires the end
 * user to act — e.g. an ambiguous order-placement failure where a position may be open on the
 * exchange beyond our control. Each row carries a transparent JSON snapshot of the trade state
 * as the system knew it, so the user can reconcile against Upstox.
 *
 * status lifecycle: LIVE -> ACKNOWLEDGED (user acknowledges from the UI).
 *
 * Never throws — a failure to record must not cascade into a trading failure. A failed insert
 * is logged at ERROR (the last-resort audit).
 */
public class CriticalAlertService {

    private static final Logger log = LoggerFactory.getLogger(CriticalAlertService.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper   json;

    public CriticalAlertService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.json = JsonMapper.builder().build();
    }

    /**
     * Records a LIVE critical alert.
     *
     * @param tradeId      the trade this alert concerns (may be null if unknown)
     * @param reason       human-readable reason — what happened and what the user must check
     * @param tradeDetails any object; serialised to JSONB so the user sees the exact trade state
     *                     as the system knew it. Never include secrets/tokens.
     * @return the generated alert_id, or null if the row could not be written
     */
    public UUID record(UUID tradeId, String reason, Object tradeDetails) {
        String detailsJson;
        try {
            detailsJson = json.writeValueAsString(tradeDetails);
        } catch (Exception e) {
            // Serialisation must never block recording the alert — fall back to a minimal payload.
            log.error("critical_alert.serialize.failed tradeId={} error={}", tradeId, e.getMessage());
            detailsJson = "{\"serializationError\":\"" + e.getMessage() + "\"}";
        }
        try {
            UUID alertId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO critical_alerts (alert_id, trade_id, alert_reason, trade_details, status) " +
                    "VALUES (?, ?, ?, ?::jsonb, 'LIVE')",
                    alertId, tradeId, reason, detailsJson);
            log.error("critical_alert.recorded alertId={} tradeId={} reason={}", alertId, tradeId, reason);
            return alertId;
        } catch (Exception e) {
            log.error("critical_alert.write.failed tradeId={} reason={} error={}",
                    tradeId, reason, e.getMessage());
            return null;
        }
    }
}
