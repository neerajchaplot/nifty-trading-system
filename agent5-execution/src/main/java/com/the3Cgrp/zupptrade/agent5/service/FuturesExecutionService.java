package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxGttClient;
import com.the3Cgrp.zupptrade.agent5.client.request.PlaceGttRequest;
import com.the3Cgrp.zupptrade.agent5.client.response.PlaceGttResponse;
import com.the3Cgrp.zupptrade.agent5.dto.FuturesGttResponse;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import com.the3Cgrp.zupptrade.shared.util.FuturesTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Places the multi-leg GTT (OCO) for a CONFIRMED futures plan (spec §4), then hands the trade to
 * the broker's GTT to manage. Every unrecoverable failure raises a CRITICAL alert (same path as
 * the options executor) so it surfaces on the UI critical-alert card and the user can act.
 *
 * The order carries the deterministic tag {@link FuturesTag#of(UUID)} — the linkage Agent 4 uses
 * to attribute realized P&L back to this plan.
 */
@Service
public class FuturesExecutionService {

    private static final Logger log = LoggerFactory.getLogger(FuturesExecutionService.class);
    private static final String LOT_SIZE_KEY = "nifty.lot.size";
    // SPAN+exposure approximation for the futures leg; the funds check is the real guard. Calibratable.
    private static final BigDecimal MARGIN_PCT = new BigDecimal("0.12");

    private final JdbcTemplate jdbc;
    private final UpstoxGttClient gttClient;
    private final MarginCheckService marginCheckService;
    private final AlertService alertService;

    public FuturesExecutionService(JdbcTemplate jdbc, UpstoxGttClient gttClient,
                                   MarginCheckService marginCheckService, AlertService alertService) {
        this.jdbc = jdbc;
        this.gttClient = gttClient;
        this.marginCheckService = marginCheckService;
        this.alertService = alertService;
    }

    public FuturesGttResponse placeGtt(UUID planId) {
        PlanRow plan = readPlan(planId);
        if (plan == null) {
            throw new IllegalArgumentException("Futures plan not found: " + planId);
        }

        // Idempotency: Agent 3 may retry. A plan already FILLED is not re-placed.
        if (plan.status == FuturePlanStatus.FILLED) {
            log.info("agent5.futures.already_filled planId={} gttOrderId={}", planId, plan.gttOrderId);
            return new FuturesGttResponse(planId, plan.gttOrderId, "FILLED", "Already placed");
        }
        if (plan.status != FuturePlanStatus.CONFIRMED) {
            throw new IllegalStateException("Plan " + planId + " not in CONFIRMED status: " + plan.status);
        }

        // Guard rails — each failure is a critical alert + EXECUTION_FAILED.
        if (plan.instrumentKey == null || plan.instrumentKey.isBlank()) {
            return fail(planId, "futures_instrument_unresolved",
                    "Futures instrument not resolved — cannot place GTT for plan " + planId);
        }
        if (plan.lots <= 0 || plan.entry == null || plan.stop == null || plan.target == null) {
            return fail(planId, "futures_gtt_failed",
                    "Plan " + planId + " missing sizing/levels — cannot place GTT");
        }

        int lotSize = fetchLotSize();
        int quantity = plan.lots * lotSize;

        BigDecimal required = plan.entry.multiply(BigDecimal.valueOf(quantity)).multiply(MARGIN_PCT);
        BigDecimal available;
        try {
            available = marginCheckService.utilization().availableMargin();
        } catch (Exception e) {
            return fail(planId, "futures_margin_insufficient",
                    "Margin check failed (Upstox funds unavailable) for plan " + planId + ": " + e.getMessage());
        }
        if (available.compareTo(required) < 0) {
            return fail(planId, "futures_margin_insufficient", String.format(
                    "Insufficient margin for plan %s: required ~₹%.0f, available ₹%.0f", planId, required, available));
        }

        FutureArmType arm = FutureArmType.valueOf(plan.primaryArm);
        String txn = arm.isLong() ? "BUY" : "SELL";
        String tag = FuturesTag.of(planId);
        PlaceGttRequest req = PlaceGttRequest.oco(
                plan.instrumentKey, txn, quantity, plan.entry, plan.target, plan.stop, tag);

        PlaceGttResponse res;
        try {
            res = gttClient.placeGtt(req);
        } catch (Exception e) {
            return fail(planId, "futures_gtt_failed",
                    "GTT placement error for plan " + planId + " (tag " + tag + "): " + e.getMessage());
        }
        if (res == null || !res.isApiSuccess() || res.gttOrderId() == null) {
            return fail(planId, "futures_gtt_failed",
                    "GTT placement rejected for plan " + planId + " (tag " + tag + ")");
        }

        markFilled(planId, res.gttOrderId(), plan.entry);
        alertService.info(planId, "futures_gtt_placed",
                "GTT placed for plan " + planId + " (" + txn + " " + quantity + " @ " + plan.entry + ", tag " + tag + ")");
        log.info("agent5.futures.placed", kv("planId", planId), kv("gttOrderId", res.gttOrderId()),
                kv("tag", tag), kv("txn", txn), kv("qty", quantity));
        return new FuturesGttResponse(planId, res.gttOrderId(), "FILLED", "GTT placed");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FuturesGttResponse fail(UUID planId, String event, String message) {
        alertService.critical(planId, event, message);
        jdbc.update("UPDATE trade_future_ledger SET status = 'EXECUTION_FAILED', updated_at = NOW() WHERE id = ?",
                planId);
        log.error("agent5.futures.failed", kv("planId", planId), kv("event", event), kv("message", message));
        return new FuturesGttResponse(planId, null, "EXECUTION_FAILED", message);
    }

    private void markFilled(UUID planId, String gttOrderId, BigDecimal entry) {
        jdbc.update("""
                UPDATE trade_future_ledger
                SET status = 'FILLED', gtt_order_id = ?, fill_price = ?, updated_at = NOW()
                WHERE id = ?
                """, gttOrderId, entry, planId);
    }

    private int fetchLotSize() {
        Integer lot = jdbc.queryForObject(
                "SELECT (value->>'lotSize')::int FROM reference_data WHERE key = ?", Integer.class, LOT_SIZE_KEY);
        if (lot == null || lot <= 0) {
            throw new IllegalStateException("Nifty lot size not found in reference_data");
        }
        return lot;
    }

    private PlanRow readPlan(UUID planId) {
        try {
            return jdbc.queryForObject("""
                    SELECT status, primary_arm, entry_price, stop_price, target_price,
                           instrument_key, gtt_order_id, (sizing->>'lots')::int AS lots
                    FROM trade_future_ledger WHERE id = ?
                    """, (rs, n) -> new PlanRow(
                        FuturePlanStatus.valueOf(rs.getString("status")),
                        rs.getString("primary_arm"),
                        rs.getBigDecimal("entry_price"),
                        rs.getBigDecimal("stop_price"),
                        rs.getBigDecimal("target_price"),
                        rs.getString("instrument_key"),
                        rs.getString("gtt_order_id"),
                        rs.getObject("lots") != null ? rs.getInt("lots") : 0),
                    planId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    record PlanRow(
            FuturePlanStatus status, String primaryArm,
            BigDecimal entry, BigDecimal stop, BigDecimal target,
            String instrumentKey, String gttOrderId, int lots) {}
}
