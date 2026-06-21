package com.the3Cgrp.zupptrade.ledger;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Append-only audit trail for every trade lifecycle state change.
 *
 * Design principles:
 *   - REQUIRED propagation: ledger write joins the caller's transaction when one exists
 *     (Agent 2 — FK constraint is satisfied since trade is in the same tx) or creates
 *     a new transaction when there is none (Agent 5, Agent 3 run without @Transactional).
 *   - Fail loudly: any DB error propagates to the caller. Agent 5 wraps post-Upstox
 *     calls in try/catch (position on exchange takes precedence over audit consistency).
 *   - Payload is typed at call-site (compile-time safety) but stored as JSONB —
 *     preserves full queryability without a wide-column anti-pattern.
 *   - occurred_by format: "AGENT2:SYSTEM", "AGENT2:USER", "AGENT5:SYSTEM", "AGENT3:SCHEDULER"
 *
 * Pattern: Repository (plain JdbcTemplate — no JPA entity needed for append-only table).
 */
public class TradeLedgerService {

    private static final Logger log = LoggerFactory.getLogger(TradeLedgerService.class);

    private static final String INSERT_SQL = """
            INSERT INTO trade_ledger (trade_id, event_type, payload, occurred_by)
            VALUES (?::uuid, ?, ?::jsonb, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper  objectMapper;

    public TradeLedgerService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Records a ledger entry.
     *
     * @param tradeId    the trade this event belongs to
     * @param eventType  the lifecycle event type
     * @param payload    a typed payload record — serialised to JSONB
     * @param occurredBy actor string e.g. "AGENT2:SYSTEM", "AGENT2:USER"
     *
     * @throws RuntimeException on DB error — fail loudly, never swallow
     */
    @Transactional
    public void record(UUID tradeId, LedgerEventType eventType, Object payload, String occurredBy) {
        String payloadJson = serialise(payload);
        log.debug("ledger.record tradeId={} event={} occurredBy={}", tradeId, eventType, occurredBy);
        jdbc.update(INSERT_SQL, tradeId.toString(), eventType.name(), payloadJson, occurredBy);
    }

    private String serialise(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // Serialisation failure is a programming error — fail loudly
            throw new IllegalArgumentException("ledger.serialise.failed payload=" + payload.getClass().getSimpleName(), e);
        }
    }
}
