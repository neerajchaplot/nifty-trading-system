package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse.TaggedOrder;
import com.the3Cgrp.zupptrade.agent5.dto.FuturesCloseResponse;
import com.the3Cgrp.zupptrade.agent5.service.FuturesCandleReplay.ReplayResult;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient.IntradayCandle;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import com.the3Cgrp.zupptrade.shared.enums.TradeDirection;
import com.the3Cgrp.zupptrade.shared.util.FuturesTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * End-of-day close for a FILLED futures plan (spec §6.7). Branches on the owning user's account
 * mode (user_profiles.account_mode):
 *
 *   LIVE       → resolve realized P&L from the broker orders under the plan's tag (Σ SELL − Σ BUY
 *                over the flat round-trip). Unresolved round-trip → CRITICAL alert, left for review.
 *   SIMULATION → replay the day's 5-min candles from the fill time to find where the GTT OCO would
 *                have exited (target / stop / square-off) and book that realized P&L.
 */
@Service
public class FuturesCloseService {

    private static final Logger log = LoggerFactory.getLogger(FuturesCloseService.class);
    private static final String LOT_SIZE_KEY = "nifty.lot.size";
    private static final BigDecimal CLOSE_REASON_TOLERANCE_PTS = new BigDecimal("25");

    private final JdbcTemplate jdbc;
    private final UpstoxOrderClient orderClient;
    private final AlertService alertService;
    private final FuturesCandleReplay candleReplay;
    private final FuturesDayCandleProvider dayCandleProvider;

    public FuturesCloseService(JdbcTemplate jdbc, UpstoxOrderClient orderClient, AlertService alertService,
                               FuturesCandleReplay candleReplay, FuturesDayCandleProvider dayCandleProvider) {
        this.jdbc = jdbc;
        this.orderClient = orderClient;
        this.alertService = alertService;
        this.candleReplay = candleReplay;
        this.dayCandleProvider = dayCandleProvider;
    }

    public FuturesCloseResponse closePlan(UUID planId) {
        PlanRow plan = readPlan(planId);
        if (plan == null) {
            throw new IllegalArgumentException("Futures plan not found: " + planId);
        }
        if (plan.status == FuturePlanStatus.CLOSED) {
            return new FuturesCloseResponse(planId, plan.realizedPnl, plan.closeReason, "CLOSED", "Already closed");
        }
        if (plan.status != FuturePlanStatus.FILLED) {
            // Nothing live to close (e.g. EXECUTION_FAILED / EXPIRED) — no-op.
            return new FuturesCloseResponse(planId, null, null, plan.status.name(), "Not a live position");
        }

        return "SIMULATION".equalsIgnoreCase(plan.accountMode)
                ? closeSimulated(planId, plan)
                : closeLive(planId, plan);
    }

    // ── SIMULATION: candle replay ──────────────────────────────────────────────

    private FuturesCloseResponse closeSimulated(UUID planId, PlanRow plan) {
        if (plan.fillPrice == null || plan.activatedAt == null || plan.lots <= 0
                || plan.target == null || plan.stop == null || plan.primaryArm == null) {
            return unresolved(planId, "SIM", "missing fill/levels for candle replay");
        }
        long qty = (long) plan.lots * fetchLotSize();
        TradeDirection dir = FutureArmType.valueOf(plan.primaryArm).direction();
        List<IntradayCandle> candles = dayCandleProvider.todayNifty5m();

        ReplayResult r = candleReplay.replay(candles, plan.fillPrice, plan.target, plan.stop,
                dir, plan.activatedAt, qty);

        bookClosed(planId, r.realizedPnl(), r.closeReason());
        alertService.info(planId, "futures_closed",
                "Simulated close for plan " + planId + ": " + r.closeReason() + ", P&L ₹" + r.realizedPnl());
        log.info("agent5.futures.closed_sim", kv("planId", planId), kv("exit", r.exitPrice()),
                kv("realized", r.realizedPnl()), kv("reason", r.closeReason()));
        return new FuturesCloseResponse(planId, r.realizedPnl(), r.closeReason(), "CLOSED", "Closed (simulated)");
    }

    // ── LIVE: realized P&L from broker orders by tag ───────────────────────────

    private FuturesCloseResponse closeLive(UUID planId, PlanRow plan) {
        String tag = FuturesTag.of(planId);
        List<TaggedOrder> filled;
        try {
            TaggedOrdersResponse resp = orderClient.getOrderDetailsByTag(tag);
            filled = (resp.data() == null ? List.<TaggedOrder>of() : resp.data()).stream()
                    .filter(o -> "complete".equalsIgnoreCase(o.orderStatus()) && o.filledQuantity() > 0)
                    .toList();
        } catch (Exception e) {
            return unresolved(planId, tag, "broker order lookup failed: " + e.getMessage());
        }

        long buyQty = sumQty(filled, "BUY");
        long sellQty = sumQty(filled, "SELL");
        if (buyQty == 0 || sellQty == 0 || buyQty != sellQty) {
            return unresolved(planId, tag,
                    "no flat round-trip under tag (buyQty=" + buyQty + " sellQty=" + sellQty + ")");
        }

        BigDecimal buyNotional = notional(filled, "BUY");
        BigDecimal sellNotional = notional(filled, "SELL");
        BigDecimal realized = sellNotional.subtract(buyNotional).setScale(2, RoundingMode.HALF_UP);

        boolean isLong = FutureArmType.valueOf(plan.primaryArm).isLong();
        BigDecimal exitAvg = isLong
                ? sellNotional.divide(BigDecimal.valueOf(sellQty), 2, RoundingMode.HALF_UP)
                : buyNotional.divide(BigDecimal.valueOf(buyQty), 2, RoundingMode.HALF_UP);
        String closeReason = closeReason(exitAvg, plan.target, plan.stop);

        bookClosed(planId, realized, closeReason);
        alertService.info(planId, "futures_closed",
                "Futures plan " + planId + " closed: " + closeReason + ", P&L ₹" + realized);
        log.info("agent5.futures.closed", kv("planId", planId), kv("tag", tag),
                kv("realized", realized), kv("closeReason", closeReason));
        return new FuturesCloseResponse(planId, realized, closeReason, "CLOSED", "Closed");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void bookClosed(UUID planId, BigDecimal realized, String closeReason) {
        jdbc.update("""
                UPDATE trade_future_ledger
                SET status = 'CLOSED', realized_pnl = ?, close_reason = ?, closed_at = NOW(), updated_at = NOW()
                WHERE id = ?
                """, realized, closeReason, planId);
    }

    private FuturesCloseResponse unresolved(UUID planId, String tag, String detail) {
        String msg = "EOD close unresolved for plan " + planId + " (" + tag + "): " + detail
                + ". Check the Upstox order book and close/settle manually.";
        alertService.critical(planId, "futures_eod_unresolved", msg);
        log.error("agent5.futures.eod_unresolved", kv("planId", planId), kv("tag", tag), kv("detail", detail));
        return new FuturesCloseResponse(planId, null, null, "UNRESOLVED", msg);
    }

    private int fetchLotSize() {
        Integer lot = jdbc.queryForObject(
                "SELECT (value->>'lotSize')::int FROM reference_data WHERE key = ?", Integer.class, LOT_SIZE_KEY);
        if (lot == null || lot <= 0) {
            throw new IllegalStateException("Nifty lot size not found in reference_data");
        }
        return lot;
    }

    private long sumQty(List<TaggedOrder> orders, String side) {
        return orders.stream().filter(o -> side.equalsIgnoreCase(o.transactionType()))
                .mapToLong(TaggedOrder::filledQuantity).sum();
    }

    private BigDecimal notional(List<TaggedOrder> orders, String side) {
        return orders.stream().filter(o -> side.equalsIgnoreCase(o.transactionType()))
                .map(o -> o.averagePrice().multiply(BigDecimal.valueOf(o.filledQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String closeReason(BigDecimal exitAvg, BigDecimal target, BigDecimal stop) {
        BigDecimal dTarget = target != null ? exitAvg.subtract(target).abs() : null;
        BigDecimal dStop = stop != null ? exitAvg.subtract(stop).abs() : null;
        boolean nearTarget = dTarget != null && dTarget.compareTo(CLOSE_REASON_TOLERANCE_PTS) <= 0;
        boolean nearStop = dStop != null && dStop.compareTo(CLOSE_REASON_TOLERANCE_PTS) <= 0;
        if (nearTarget && (!nearStop || dTarget.compareTo(dStop) <= 0)) return "Target hit";
        if (nearStop) return "Stop hit";
        return "Square-off";
    }

    private PlanRow readPlan(UUID planId) {
        try {
            return jdbc.queryForObject("""
                    SELECT t.status, t.primary_arm, t.target_price, t.stop_price, t.fill_price,
                           t.activated_at, (t.sizing->>'lots')::int AS lots, t.realized_pnl,
                           t.close_reason, up.account_mode
                    FROM trade_future_ledger t JOIN user_profiles up ON up.id = t.user_profile_id
                    WHERE t.id = ?
                    """, (rs, n) -> new PlanRow(
                        FuturePlanStatus.valueOf(rs.getString("status")),
                        rs.getString("primary_arm"),
                        rs.getBigDecimal("target_price"),
                        rs.getBigDecimal("stop_price"),
                        rs.getBigDecimal("fill_price"),
                        rs.getObject("activated_at", OffsetDateTime.class),
                        rs.getObject("lots") != null ? rs.getInt("lots") : 0,
                        rs.getBigDecimal("realized_pnl"),
                        rs.getString("close_reason"),
                        rs.getString("account_mode")),
                    planId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    record PlanRow(FuturePlanStatus status, String primaryArm, BigDecimal target, BigDecimal stop,
                   BigDecimal fillPrice, OffsetDateTime activatedAt, int lots,
                   BigDecimal realizedPnl, String closeReason, String accountMode) {}
}
