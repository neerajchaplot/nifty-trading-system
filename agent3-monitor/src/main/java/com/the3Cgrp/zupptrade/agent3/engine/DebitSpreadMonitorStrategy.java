package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.math.BlackScholesCalculator;
import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.agent3.model.MonitorEvaluationContext;
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
 * Monitors BULL_CALL_SPREAD and BEAR_PUT_SPREAD (debit spreads) — direction-agnostic PoP model.
 *
 * A debit spread is defined-risk (max loss = premium) and has NO readjustment. Two live probabilities
 * drive the decision, both DTE/IV-adaptive:
 *   PoP  = directional probability spot finishes past the BREAKEVEN (any profit).
 *   PoPP = directional probability spot finishes past the SHORT strike (max profit).
 * ("Directional": bull call = P(spot &gt; level); bear put = P(spot &lt; level).)
 *
 * Decision priority:
 *   1. VIX Extreme (&gt;24)                                   → PAUSE
 *   2. DTE = 0 (PoP is binary at expiry)                    → EXIT (close out)
 *   3. Disaster stop:  PoP ≤ entryPoP × (1 − 20%)           → EXIT  (loss-cut)
 *   4. Give-back lock: (PoP−PoPP) ≥ entryGap + 10pp         → EXIT  (lock the winner's give-back)
 *   5. else                                                 → HOLD  (ride toward max profit)
 *
 * Levels shown on the UI: Breakeven (ref), Profit-Book = short strike, and a dynamic Loss-Cut Nifty
 * level (spot where PoP hits the disaster floor, recomputed each cycle).
 */
@Component
public class DebitSpreadMonitorStrategy implements MonitorStrategy {

    /** Pure decision outcome — unit-tested directly. */
    public enum DebitAction { HOLD, EXIT_DISASTER, EXIT_GIVEBACK }

    private final PnlCalculationService pnlService;
    private final BlackScholesCalculator blackScholes;
    private final MonitoringProperties props;

    public DebitSpreadMonitorStrategy(PnlCalculationService pnlService,
                                       BlackScholesCalculator blackScholes,
                                       MonitoringProperties props) {
        this.pnlService = pnlService;
        this.blackScholes = blackScholes;
        this.props = props;
    }

    @Override
    public Strategy getStrategy() {
        // Factory routes both BULL_CALL_SPREAD and BEAR_PUT_SPREAD here.
        return Strategy.BULL_CALL_SPREAD;
    }

    /**
     * Pure, deterministic decision. Same inputs → same output; no Spring/market dependencies.
     *   Rule 1 (disaster): livePoP ≤ entryPoP × (1 − dropFraction)          → EXIT_DISASTER
     *   Rule 2 (give-back): (livePoP − livePoPP) − (entryPoP − entryPoPP) ≥ gapWiden → EXIT_GIVEBACK
     *   else HOLD. Direction-agnostic — works for bull call and bear put alike.
     */
    static DebitAction decideDebit(BigDecimal entryPop, BigDecimal entryPopp,
                                   BigDecimal livePop, BigDecimal livePopp,
                                   BigDecimal dropFraction, BigDecimal gapWidenThreshold) {
        if (entryPop == null || livePop == null) return DebitAction.HOLD;
        BigDecimal floor = entryPop.multiply(BigDecimal.ONE.subtract(dropFraction));
        if (livePop.compareTo(floor) <= 0) return DebitAction.EXIT_DISASTER;
        if (entryPopp != null && livePopp != null) {
            BigDecimal entryGap = entryPop.subtract(entryPopp);
            BigDecimal liveGap  = livePop.subtract(livePopp);
            if (liveGap.subtract(entryGap).compareTo(gapWidenThreshold) >= 0) return DebitAction.EXIT_GIVEBACK;
        }
        return DebitAction.HOLD;
    }

    @Override
    public EvaluationResult evaluate(MonitorEvaluationContext context) {
        MonitorConfigDto config  = context.config();
        MonitorThresholdsDto thr = config.thresholds();
        BigDecimal spot          = context.liveData().spot();
        BigDecimal vix           = context.liveData().vix();
        BigDecimal shortLegLtp   = context.liveData().shortLegLtp();
        BigDecimal longLegLtp    = context.liveData().longLegLtp();
        BigDecimal iv            = context.liveData().shortLegIv();   // vol proxy for the underlying
        int currentDte           = context.currentDte();

        boolean isBullCall = config.longLeg() != null && config.longLeg().optionType() == OptionType.CE;
        BigDecimal breakeven   = thr != null ? thr.t1WatchNiftyLevel()    : null;  // debit: t1 = breakeven
        BigDecimal shortStrike = thr != null ? thr.t2ReadjustNiftyLevel() : null;  // debit: t2 = short strike
        OptionType popType     = isBullCall ? OptionType.PE : OptionType.CE;        // PE→N(d2)=P(>level); CE→N(-d2)=P(<level)

        Map<String, Object> detail = new HashMap<>();
        detail.put("spot", spot);
        detail.put("vix", vix);
        detail.put("currentDte", currentDte);
        detail.put("shortLegLtp", shortLegLtp);
        detail.put("longLegLtp", longLegLtp);
        detail.put("shortLegIv", iv);   // audit — the input that drives livePop/PoPP and the loss-cut level
        if (breakeven != null)   detail.put("liveBreakevenLevel", breakeven);
        if (shortStrike != null) detail.put("liveProfitBookLevel", shortStrike);  // max-profit = short strike

        // --- 1. VIX Extreme ---
        if (vix != null && vix.compareTo(props.getVixExtremeThreshold()) > 0) {
            return result(MonitorAction.PAUSE, ThresholdHit.VIX_EXTREME_PAUSE,
                    String.format("VIX=%.2f exceeds extreme threshold (%.0f) — auto-trading paused.",
                            vix, props.getVixExtremeThreshold()),
                    null, null, null, detail);
        }

        BigDecimal mtmPnl = (spot != null && shortLegLtp != null && longLegLtp != null)
                ? pnlService.calculateMtmPnl(config, shortLegLtp, longLegLtp) : null;
        BigDecimal currentNetPremium = (shortLegLtp != null && longLegLtp != null)
                ? pnlService.currentNetPremium(config.spreadDirection(), shortLegLtp, longLegLtp) : null;
        if (mtmPnl != null) detail.put("markToMarketPnl", mtmPnl);

        // --- 2. Expiry day: PoP is binary → close the defined-risk position out ---
        if (currentDte <= 0) {
            return result(MonitorAction.EXIT, ThresholdHit.DEBIT_EXPIRY_EXIT,
                    String.format("EXPIRY DAY (DTE=0) — closing debit spread. Spot=%s MTM P&L=%s",
                            fmt(spot), fmt(mtmPnl)),
                    currentNetPremium, mtmPnl, null, detail);
        }

        // --- Market data / IV required for PoP ---
        if (spot == null || breakeven == null || shortStrike == null || iv == null || iv.signum() <= 0) {
            return result(MonitorAction.WATCH, ThresholdHit.NONE,
                    "Market data / IV unavailable — holding conservatively at WATCH.",
                    currentNetPremium, mtmPnl, null, detail);
        }

        // --- Live directional PoP / PoPP ---
        BigDecimal r = props.getRiskFreeRate();
        BigDecimal livePop  = blackScholes.calculatePop(spot, breakeven,   iv, currentDte, r, popType);
        BigDecimal livePopp = blackScholes.calculatePop(spot, shortStrike, iv, currentDte, r, popType);
        BigDecimal entryPop  = thr.entryPop();
        BigDecimal entryPopp = thr.entryPopp();
        detail.put("livePop", livePop);
        detail.put("livePopp", livePopp);
        detail.put("liveGap", livePop.subtract(livePopp));
        if (entryPop != null && entryPopp != null) {
            detail.put("entryPop", entryPop);
            detail.put("entryPopp", entryPopp);
            detail.put("entryGap", entryPop.subtract(entryPopp));
        }

        // Dynamic Loss-Cut Nifty level (spot where PoP hits the disaster floor) — for the UI.
        if (entryPop != null) {
            double floorPop = entryPop.multiply(BigDecimal.ONE.subtract(props.getDebitPopDropFraction())).doubleValue();
            BigDecimal lossCut = blackScholes.inversePopSpot(breakeven, iv, currentDte, r, floorPop, popType);
            if (lossCut != null) detail.put("liveLossCutLevel", lossCut);
        }

        // --- 3 & 4. PoP decision ---
        DebitAction action = decideDebit(entryPop, entryPopp, livePop, livePopp,
                props.getDebitPopDropFraction(), props.getDebitGapWidenThreshold());

        return switch (action) {
            case EXIT_DISASTER -> result(MonitorAction.EXIT, ThresholdHit.DEBIT_POP_DISASTER,
                    String.format("LOSS-CUT: PoP=%.1f%% fell ≥%.0f%% below entry PoP=%.1f%%. Closing. MTM P&L=%s",
                            pct(livePop), props.getDebitPopDropFraction().doubleValue() * 100, pct(entryPop), fmt(mtmPnl)),
                    currentNetPremium, mtmPnl, livePop, detail);
            case EXIT_GIVEBACK -> result(MonitorAction.EXIT, ThresholdHit.DEBIT_GIVEBACK_LOCK,
                    String.format("GIVE-BACK LOCK: PoP−PoPP gap=%.1fpp widened ≥%.0fpp above entry gap. Booking. MTM P&L=%s",
                            (livePop.subtract(livePopp)).doubleValue() * 100,
                            props.getDebitGapWidenThreshold().doubleValue() * 100, fmt(mtmPnl)),
                    currentNetPremium, mtmPnl, livePop, detail);
            case HOLD -> result(MonitorAction.HOLD, ThresholdHit.NONE,
                    String.format("Riding toward max profit. Spot=%.2f DTE=%d PoP=%.1f%% PoPP=%.1f%% MTM P&L=%s",
                            spot, currentDte, pct(livePop), pct(livePopp), fmt(mtmPnl)),
                    currentNetPremium, mtmPnl, livePop, detail);
        };
    }

    private static double pct(BigDecimal fraction) {
        return fraction == null ? 0.0 : fraction.doubleValue() * 100;
    }

    private static String fmt(BigDecimal v) {
        return v != null ? String.format("%.2f", v) : "unknown";
    }

    private EvaluationResult result(MonitorAction action, ThresholdHit hit, String reason,
                                     BigDecimal netPremium, BigDecimal pnl, BigDecimal pop,
                                     Map<String, Object> detail) {
        detail.put("action", action.name());
        detail.put("thresholdHit", hit.name());
        return new EvaluationResult(action, hit, reason, netPremium, pnl, pop, detail);
    }
}
