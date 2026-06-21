package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.agent3.model.MonitorEvaluationContext;
import com.the3Cgrp.zupptrade.agent3.service.LivePopService;
import com.the3Cgrp.zupptrade.agent3.service.PnlCalculationService;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.enums.MonitorAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.ThresholdHit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Monitors BULL_PUT_SPREAD and BEAR_CALL_SPREAD (credit spreads).
 *
 * Decision priority for normal days (DTE > 0, highest to lowest):
 *   1. VIX Extreme (>24)                        → PAUSE
 *   2. T3: short strike breach                  → EXIT  (ITM — PoP irrelevant)
 *   3. T3: MTM loss ≥ t3LossThreshold           → EXIT
 *   4. Live PoP < 65%                           → EXIT
 *   5. Live PoP 65–74%                          → READJUST
 *   6. T2 P&L: MTM loss ≥ t2LossThreshold       → READJUST (escalate from HOLD/WATCH)
 *   7. Live PoP 75–79%                          → WATCH
 *   8. Live PoP ≥ 80%                           → HOLD
 *
 * Expiry day (DTE=0) — PoP ladder replaced with proximity-based logic:
 *   Black-Scholes returns binary 1.0/0.0 at DTE=0 — no useful intermediate zone.
 *   Instead, distance of spot from short strike drives the decision.
 *   1. VIX Extreme                              → PAUSE
 *   2. Short strike breached (ITM)              → EXIT
 *   3. MTM loss ≥ t3LossThreshold               → EXIT
 *   4. Spot within expiryDayExitBufferPts (75)  → EXIT  (imminent breach, gamma too high)
 *   5. Spot within expiryDayWatchBufferPts (150)→ WATCH
 *   6. Otherwise                                → HOLD
 *
 * Null PoP (IV unavailable on normal days) is treated conservatively as WATCH.
 */
@Component
public class CreditSpreadMonitorStrategy implements MonitorStrategy {

    private final PnlCalculationService pnlService;
    private final LivePopService livePopService;
    private final MonitoringProperties props;

    public CreditSpreadMonitorStrategy(PnlCalculationService pnlService,
                                        LivePopService livePopService,
                                        MonitoringProperties props) {
        this.pnlService = pnlService;
        this.livePopService = livePopService;
        this.props = props;
    }

    @Override
    public Strategy getStrategy() {
        // Handles both credit spread types — factory routes both here
        return Strategy.BULL_PUT_SPREAD;
    }

    @Override
    public EvaluationResult evaluate(MonitorEvaluationContext context) {
        MonitorConfigDto config  = context.config();
        MonitorThresholdsDto thr = config.thresholds();
        BigDecimal spot          = context.liveData().spot();
        BigDecimal vix           = context.liveData().vix();
        BigDecimal shortLegLtp   = context.liveData().shortLegLtp();
        BigDecimal longLegLtp    = context.liveData().longLegLtp();
        BigDecimal shortLegIv    = context.liveData().shortLegIv();
        int currentDte           = context.currentDte();

        Map<String, Object> detail = new HashMap<>();
        detail.put("spot", spot);
        detail.put("vix", vix);
        detail.put("currentDte", currentDte);
        detail.put("shortLegLtp", shortLegLtp);
        detail.put("longLegLtp", longLegLtp);
        detail.put("shortLegIv", shortLegIv);

        // --- 1. VIX Extreme ---
        if (vix != null && vix.compareTo(props.getVixExtremeThreshold()) > 0) {
            return result(MonitorAction.PAUSE, ThresholdHit.VIX_EXTREME_PAUSE,
                    String.format("VIX=%.2f exceeds extreme threshold (%.0f) — auto-trading paused. Manual review required.",
                            vix, props.getVixExtremeThreshold()),
                    null, null, null, detail);
        }

        // --- 2. Expiry Day (DTE=0): switch to proximity-based logic ---
        // Black-Scholes is degenerate at t=0 (returns binary 1.0/0.0 at the strike boundary).
        // PoP gives no useful intermediate signal on expiry day — use spot distance instead.
        if (currentDte == 0) {
            return evaluateExpiryDay(config, spot, shortLegLtp, longLegLtp, thr, detail);
        }

        // Market data required from here on
        if (spot == null || shortLegLtp == null || longLegLtp == null) {
            return result(MonitorAction.WATCH, ThresholdHit.NONE,
                    "Market data unavailable — holding conservatively at WATCH pending next cycle.",
                    null, null, null, detail);
        }

        BigDecimal currentNetPremium = pnlService.currentNetPremium(
                config.spreadDirection(), shortLegLtp, longLegLtp);
        BigDecimal mtmPnl = pnlService.calculateMtmPnl(config, shortLegLtp, longLegLtp);
        detail.put("currentNetPremium", currentNetPremium);
        detail.put("markToMarketPnl", mtmPnl);

        // --- 3. T3: Short strike breach (always EXIT regardless of PoP) ---
        if (isShortStrikeBreach(config, spot)) {
            return result(MonitorAction.EXIT, ThresholdHit.T3_SHORT_STRIKE_BREACH,
                    String.format("SHORT STRIKE BREACHED. Spot=%.2f has moved through short strike=%d. " +
                            "Option is ITM. Closing all legs immediately. MTM P&L=%.2f",
                            spot, config.shortLeg().strike(), mtmPnl),
                    currentNetPremium, mtmPnl, null, detail);
        }

        // --- 4. T3: MTM loss threshold ---
        if (thr.t3LossThreshold() != null
                && pnlService.hasBreachedLossThreshold(mtmPnl, thr.t3LossThreshold())) {
            return result(MonitorAction.EXIT, ThresholdHit.T3_EXIT_PNL,
                    String.format("T3 LOSS STOP hit. MTM P&L=%.2f breached threshold=%.2f. Closing all legs.",
                            mtmPnl, thr.t3LossThreshold().negate()),
                    currentNetPremium, mtmPnl, null, detail);
        }

        // --- 5–8. Live PoP decision ---
        BigDecimal livePop = livePopService.calculateLivePop(config, spot, shortLegIv, currentDte);
        detail.put("livePop", livePop);

        boolean vixSpike = livePopService.isVixSpike(vix, context.previousVix());
        String vixNote = vixSpike
                ? String.format(" [VIX spike detected: %.2f → %.2f, PoP recalculated with live IV]",
                        context.previousVix(), vix)
                : "";

        if (livePop == null) {
            // IV unavailable — conservative WATCH; P&L override still applies below
            MonitorAction action = MonitorAction.WATCH;
            ThresholdHit hit = ThresholdHit.NONE;
            if (thr.t2LossThreshold() != null
                    && pnlService.hasBreachedLossThreshold(mtmPnl, thr.t2LossThreshold())) {
                action = MonitorAction.READJUST;
                hit = ThresholdHit.T2_READJUST_PNL;
            }
            return result(action, hit,
                    String.format("PoP unavailable (IV missing). MTM P&L=%.2f. Action=%s.%s",
                            mtmPnl, action, vixNote),
                    currentNetPremium, mtmPnl, null, detail);
        }

        double popDouble = livePop.doubleValue();

        if (popDouble < props.getPopReadjustMinimum().doubleValue()) {
            return result(MonitorAction.EXIT, ThresholdHit.POP_EXIT,
                    String.format("PoP=%.1f%% below EXIT threshold (%.0f%%). Spot=%.2f, short strike=%d. MTM P&L=%.2f.%s",
                            popDouble * 100, props.getPopReadjustMinimum().doubleValue() * 100,
                            spot, config.shortLeg().strike(), mtmPnl, vixNote),
                    currentNetPremium, mtmPnl, livePop, detail);
        }

        if (popDouble < props.getPopWatchMinimum().doubleValue()) {
            String t2NiftyNote = (thr.t2ReadjustNiftyLevel() != null)
                    ? String.format(" T2 level=%.0f.", thr.t2ReadjustNiftyLevel().doubleValue()) : "";
            return result(MonitorAction.READJUST, ThresholdHit.POP_READJUST,
                    String.format("PoP=%.1f%% in READJUST zone (65–74%%). Spot=%.2f.%s MTM P&L=%.2f.%s",
                            popDouble * 100, spot, t2NiftyNote, mtmPnl, vixNote),
                    currentNetPremium, mtmPnl, livePop, detail);
        }

        // --- 7. T2 P&L escalation (override HOLD/WATCH if loss is significant) ---
        if (thr.t2LossThreshold() != null
                && pnlService.hasBreachedLossThreshold(mtmPnl, thr.t2LossThreshold())) {
            return result(MonitorAction.READJUST, ThresholdHit.T2_READJUST_PNL,
                    String.format("T2 P&L threshold hit. MTM P&L=%.2f breached T2 threshold=%.2f " +
                            "(PoP=%.1f%% still OK — P&L deteriorating faster than expected). Escalating to READJUST.",
                            mtmPnl, thr.t2LossThreshold().negate(), popDouble * 100),
                    currentNetPremium, mtmPnl, livePop, detail);
        }

        if (popDouble < props.getPopHoldMinimum().doubleValue()) {
            String t1NiftyNote = (thr.t1WatchNiftyLevel() != null)
                    ? String.format(" Nifty approaching T1 level=%.0f.", thr.t1WatchNiftyLevel().doubleValue()) : "";
            return result(MonitorAction.WATCH, ThresholdHit.POP_WATCH,
                    String.format("PoP=%.1f%% in WATCH zone (75–79%%).%s MTM P&L=%.2f.%s",
                            popDouble * 100, t1NiftyNote, mtmPnl, vixNote),
                    currentNetPremium, mtmPnl, livePop, detail);
        }

        // --- 9. HOLD ---
        return result(MonitorAction.HOLD, ThresholdHit.NONE,
                String.format("PoP=%.1f%% — trade healthy. Spot=%.2f, short strike=%d, DTE=%d. MTM P&L=%.2f.%s",
                        popDouble * 100, spot, config.shortLeg().strike(), currentDte, mtmPnl, vixNote),
                currentNetPremium, mtmPnl, livePop, detail);
    }

    /**
     * Expiry day (DTE=0) evaluation — proximity-based decision replaces the PoP ladder.
     * Called exclusively when currentDte == 0.
     *
     * Priority:
     *   a. Short strike breached (ITM)               → EXIT  T3_SHORT_STRIKE_BREACH
     *   b. MTM loss ≥ t3LossThreshold                → EXIT  T3_EXIT_PNL
     *   c. Spot within expiryDayExitBufferPts of strike → EXIT  EXPIRY_DAY_PROXIMITY_EXIT
     *   d. Spot within expiryDayWatchBufferPts of strike → WATCH EXPIRY_DAY_PROXIMITY_WATCH
     *   e. Market data unavailable                   → WATCH (conservative on expiry day)
     *   f. Safely OTM beyond watch buffer            → HOLD
     */
    private EvaluationResult evaluateExpiryDay(MonitorConfigDto config,
                                                BigDecimal spot,
                                                BigDecimal shortLegLtp,
                                                BigDecimal longLegLtp,
                                                MonitorThresholdsDto thr,
                                                Map<String, Object> detail) {
        detail.put("expiryDayMode", true);

        BigDecimal mtmPnl = (spot != null && shortLegLtp != null && longLegLtp != null)
                ? pnlService.calculateMtmPnl(config, shortLegLtp, longLegLtp) : null;
        if (mtmPnl != null) detail.put("markToMarketPnl", mtmPnl);

        // a. ITM breach — same threshold as normal T3
        if (spot != null && isShortStrikeBreach(config, spot)) {
            return result(MonitorAction.EXIT, ThresholdHit.T3_SHORT_STRIKE_BREACH,
                    String.format("EXPIRY DAY — SHORT STRIKE BREACHED. Spot=%.2f through short strike=%d. " +
                            "Option is ITM. Closing all legs immediately. MTM P&L=%s",
                            spot, config.shortLeg().strike(),
                            mtmPnl != null ? String.format("%.2f", mtmPnl) : "unknown"),
                    null, mtmPnl, null, detail);
        }

        // b. MTM loss threshold
        if (mtmPnl != null && thr.t3LossThreshold() != null
                && pnlService.hasBreachedLossThreshold(mtmPnl, thr.t3LossThreshold())) {
            return result(MonitorAction.EXIT, ThresholdHit.T3_EXIT_PNL,
                    String.format("EXPIRY DAY — T3 LOSS STOP hit. MTM P&L=%.2f breached threshold=%.2f. Closing all legs.",
                            mtmPnl, thr.t3LossThreshold().negate()),
                    null, mtmPnl, null, detail);
        }

        // No spot data on expiry day → conservative WATCH (can't assess proximity)
        if (spot == null || shortLegLtp == null || longLegLtp == null) {
            return result(MonitorAction.WATCH, ThresholdHit.NONE,
                    "EXPIRY DAY — market data unavailable. Escalating to WATCH (expiry day gamma risk).",
                    null, mtmPnl, null, detail);
        }

        int exitBuf  = props.getExpiryDayExitBufferPts();
        int watchBuf = props.getExpiryDayWatchBufferPts();

        // c. Proximity EXIT zone — spot within exitBuf pts of short strike
        if (isWithinProximityBuffer(config, spot, exitBuf)) {
            return result(MonitorAction.EXIT, ThresholdHit.EXPIRY_DAY_PROXIMITY_EXIT,
                    String.format("EXPIRY DAY — Spot=%.2f within %d pts of short strike=%d. " +
                            "High gamma risk — exiting before breach. MTM P&L=%.2f",
                            spot, exitBuf, config.shortLeg().strike(), mtmPnl),
                    null, mtmPnl, null, detail);
        }

        // d. Proximity WATCH zone — spot within watchBuf pts of short strike
        if (isWithinProximityBuffer(config, spot, watchBuf)) {
            return result(MonitorAction.WATCH, ThresholdHit.EXPIRY_DAY_PROXIMITY_WATCH,
                    String.format("EXPIRY DAY — Spot=%.2f within %d pts WATCH buffer of short strike=%d. " +
                            "Monitoring closely. MTM P&L=%.2f",
                            spot, watchBuf, config.shortLeg().strike(), mtmPnl),
                    null, mtmPnl, null, detail);
        }

        // f. HOLD — safely beyond watch buffer
        return result(MonitorAction.HOLD, ThresholdHit.NONE,
                String.format("EXPIRY DAY — Spot=%.2f safely OTM. Short strike=%d, buffer clear (>%d pts). MTM P&L=%.2f",
                        spot, config.shortLeg().strike(), watchBuf, mtmPnl),
                null, mtmPnl, null, detail);
    }

    /**
     * True when spot is within bufferPts of the short strike, in the direction of danger.
     * For short PUT: danger is spot falling toward strike from above.
     * For short CALL: danger is spot rising toward strike from below.
     */
    private boolean isWithinProximityBuffer(MonitorConfigDto config, BigDecimal spot, int bufferPts) {
        BigDecimal shortStrike = BigDecimal.valueOf(config.shortLeg().strike());
        BigDecimal buffer      = BigDecimal.valueOf(bufferPts);
        return switch (config.shortLeg().optionType()) {
            case PE -> spot.compareTo(shortStrike.add(buffer)) <= 0;
            case CE -> spot.compareTo(shortStrike.subtract(buffer)) >= 0;
        };
    }

    /** True when spot has moved through (or to) the short strike, putting it ITM. */
    private boolean isShortStrikeBreach(MonitorConfigDto config, BigDecimal spot) {
        int shortStrike = config.shortLeg().strike();
        OptionType optionType = config.shortLeg().optionType();
        return switch (optionType) {
            // Short put: loss when spot falls to or below short strike
            case PE -> spot.compareTo(BigDecimal.valueOf(shortStrike)) <= 0;
            // Short call: loss when spot rises to or above short strike
            case CE -> spot.compareTo(BigDecimal.valueOf(shortStrike)) >= 0;
        };
    }

    private EvaluationResult result(MonitorAction action, ThresholdHit hit, String reason,
                                     BigDecimal netPremium, BigDecimal pnl, BigDecimal pop,
                                     Map<String, Object> detail) {
        detail.put("action", action.name());
        detail.put("thresholdHit", hit.name());
        return new EvaluationResult(action, hit, reason, netPremium, pnl, pop, detail);
    }
}
