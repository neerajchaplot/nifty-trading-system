package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient.UpstoxOrderException;
import com.the3Cgrp.zupptrade.agent5.client.request.MarginCheckRequest;
import com.the3Cgrp.zupptrade.agent5.client.response.FundsAndMarginResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.config.Agent5ExecutionProperties;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Pre-execution margin check called by the UI before Agent 2 /confirm.
 *
 * Reads trade legs and lot info from DB (via JSON extraction), builds an Upstox
 * /v2/charges/margin request, and compares required margin against available funds.
 * All lot size and instrument key data is sourced from the DB — the caller only
 * needs to supply the trade ID and an optional lot override.
 */
@Service
public class MarginCheckService {

    private static final Logger log = LoggerFactory.getLogger(MarginCheckService.class);

    private final UpstoxOrderClient        orderClient;
    private final Agent5ExecutionProperties props;
    private final JdbcTemplate             jdbc;

    public MarginCheckService(UpstoxOrderClient orderClient,
                              Agent5ExecutionProperties props,
                              JdbcTemplate jdbc) {
        this.orderClient = orderClient;
        this.props       = props;
        this.jdbc        = jdbc;
    }

    // ── Request / Response DTOs ─────────────────────────────────────────────────

    public record MarginCheckRequestBody(
            @NotNull UUID    tradeId,
            Integer          overrideLots   // null = use the lots stored in trades.summary
    ) {}

    public record MarginCheckResultDto(
            BigDecimal requiredMargin,
            BigDecimal availableMargin,
            boolean    sufficient,
            BigDecimal shortfall   // null when sufficient
    ) {}

    // ── Public API ──────────────────────────────────────────────────────────────

    public MarginCheckResultDto check(UUID tradeId, Integer overrideLots) {
        log.info("margin.check.start", kv("tradeId", tradeId), kv("overrideLots", overrideLots));

        LotInfo lotInfo = readLotInfo(tradeId);
        if (lotInfo == null) {
            throw new MarginCheckException(
                    "Trade not found or not in PENDING_CONFIRM / CONFIRMED status: " + tradeId, null);
        }

        int effectiveLots = overrideLots != null ? overrideLots : lotInfo.lots();
        int qty           = effectiveLots * lotInfo.lotSize();

        List<MarginCheckRequest.Instrument> instruments = readLegsAsInstruments(tradeId, qty);
        if (instruments.isEmpty()) {
            throw new MarginCheckException("No legs found for trade: " + tradeId, null);
        }

        BigDecimal required  = fetchRequiredMargin(tradeId, instruments);
        BigDecimal available = fetchAvailableMargin(tradeId);

        boolean    sufficient = available.compareTo(required) >= 0;
        BigDecimal shortfall  = sufficient ? null : required.subtract(available);

        log.info("margin.check.result",
                kv("tradeId", tradeId), kv("effectiveLots", effectiveLots),
                kv("required", required), kv("available", available), kv("sufficient", sufficient));

        return new MarginCheckResultDto(required, available, sufficient, shortfall);
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private record LotInfo(int lots, int lotSize) {}

    private LotInfo readLotInfo(UUID tradeId) {
        try {
            return jdbc.queryForObject(
                    "SELECT (summary->>'lots')::int, (summary->>'lotSize')::int " +
                    "FROM trades WHERE id = ? AND status IN ('PENDING_CONFIRM', 'CONFIRMED')",
                    (rs, n) -> new LotInfo(rs.getInt(1), rs.getInt(2)),
                    tradeId);
        } catch (Exception e) {
            log.error("margin.check.db.lot_info.failed",
                    kv("tradeId", tradeId), kv("error", e.getMessage()));
            return null;
        }
    }

    // Uses jsonb_array_elements to extract one row per leg from trades.legs JSONB array.
    private List<MarginCheckRequest.Instrument> readLegsAsInstruments(UUID tradeId, int quantity) {
        return jdbc.query(
                "SELECT leg->>'instrumentKey' AS instrument_key, " +
                "       leg->>'action'        AS action, " +
                "       (leg->>'ltp')::numeric AS ltp " +
                "FROM   trades t, jsonb_array_elements(t.legs) AS leg " +
                "WHERE  t.id = ?",
                (rs, n) -> new MarginCheckRequest.Instrument(
                        rs.getString("instrument_key"),
                        rs.getString("action"),
                        quantity,
                        props.getProduct(),
                        rs.getBigDecimal("ltp")),
                tradeId);
    }

    private BigDecimal fetchRequiredMargin(UUID tradeId, List<MarginCheckRequest.Instrument> instruments) {
        MarginCheckResponse resp;
        try {
            resp = orderClient.checkMargin(new MarginCheckRequest(instruments));
        } catch (UpstoxOrderException e) {
            log.error("margin.check.upstox.error",
                    kv("tradeId", tradeId), kv("error", e.getMessage()));
            throw new MarginCheckException("Upstox margin check failed: " + e.getMessage(), e);
        }
        return resp.data().finalMargin();
    }

    private BigDecimal fetchAvailableMargin(UUID tradeId) {
        FundsAndMarginResponse resp;
        try {
            resp = orderClient.getAvailableFunds();
        } catch (UpstoxOrderException e) {
            log.error("margin.check.funds.error",
                    kv("tradeId", tradeId), kv("error", e.getMessage()));
            throw new MarginCheckException("Upstox funds check failed: " + e.getMessage(), e);
        }
        return resp.availableMargin();
    }

    // ── Exception ────────────────────────────────────────────────────────────────

    public static class MarginCheckException extends RuntimeException {
        public MarginCheckException(String msg, Throwable cause) { super(msg, cause); }
    }
}
