package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.agent3.model.MonitorEvaluationContext;
import com.the3Cgrp.zupptrade.agent3.service.LivePopService;
import com.the3Cgrp.zupptrade.agent3.service.PnlCalculationService;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.enums.MonitorAction;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.ThresholdHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Monitors IRON_CONDOR positions.
 *
 * Iron Condor = PE short spread (downside) + CE short spread (upside).
 * MonitorConfigDto carries all 4 legs:
 *   shortLeg  = PE SELL    longLeg  = PE BUY
 *   shortLeg2 = CE SELL    longLeg2 = CE BUY
 *
 * MonitorThresholdsDto carries bilateral Nifty levels:
 *   t3ExitNiftyDown = PE short strike — Nifty falling to here → EXIT
 *   t3ExitNiftyUp   = CE short strike — Nifty rising  to here → EXIT
 *
 * Decision priority:
 *   1. VIX Extreme (>24)                  → PAUSE
 *   2. Either short strike breached (ITM) → EXIT  (T3_SHORT_STRIKE_BREACH)
 *   3. MTM loss ≥ t3LossThreshold         → EXIT  (T3_EXIT_PNL)
 *   4. MTM loss ≥ t2LossThreshold         → READJUST
 *   5. Nifty in T2 zone on either side    → READJUST
 *   6. Nifty in T1 zone on either side    → WATCH
 *   7. All clear                          → HOLD
 */
@Component
public class IronCondorMonitorStrategy implements MonitorStrategy {

    private static final Logger log = LoggerFactory.getLogger(IronCondorMonitorStrategy.class);

    private final PnlCalculationService pnlService;
    private final LivePopService livePopService;
    private final MonitoringProperties props;

    public IronCondorMonitorStrategy(PnlCalculationService pnlService,
                                      LivePopService livePopService,
                                      MonitoringProperties props) {
        this.pnlService    = pnlService;
        this.livePopService = livePopService;
        this.props         = props;
    }

    @Override
    public Strategy getStrategy() {
        return Strategy.IRON_CONDOR;
    }

    @Override
    public EvaluationResult evaluate(MonitorEvaluationContext context) {
        MonitorConfigDto     config = context.config();
        MonitorThresholdsDto thr    = config.thresholds();
        BigDecimal spot             = context.liveData().spot();
        BigDecimal vix              = context.liveData().vix();
        BigDecimal shortLegLtp      = context.liveData().shortLegLtp();  // PE SELL live LTP
        BigDecimal longLegLtp       = context.liveData().longLegLtp();   // PE BUY  live LTP
        int currentDte              = context.currentDte();

        Map<String, Object> detail = new HashMap<>();
        detail.put("spot", spot);
        detail.put("vix", vix);
        detail.put("currentDte", currentDte);
        detail.put("strategy", "IRON_CONDOR");
        if (config.shortLeg()  != null) detail.put("peShortStrike", config.shortLeg().strike());
        if (config.shortLeg2() != null) detail.put("ceShortStrike", config.shortLeg2().strike());

        // ── 1. VIX Extreme ─────────────────────────────────────────────────────
        if (vix != null && vix.compareTo(props.getVixExtremeThreshold()) > 0) {
            return result(MonitorAction.PAUSE, ThresholdHit.VIX_EXTREME_PAUSE,
                    String.format("VIX=%.2f exceeds extreme threshold (%.0f) — auto-trading paused.",
                            vix, props.getVixExtremeThreshold()),
                    null, null, null, detail);
        }

        if (spot == null) {
            return result(MonitorAction.WATCH, ThresholdHit.NONE,
                    "Market data unavailable — holding conservatively at WATCH.", null, null, null, detail);
        }

        // ── 2. Short strike breach (either side) ───────────────────────────────
        if (config.shortLeg() != null && spot.compareTo(BigDecimal.valueOf(config.shortLeg().strike())) <= 0) {
            BigDecimal mtmPnl = calcMtmSafe(config, shortLegLtp, longLegLtp);
            return result(MonitorAction.EXIT, ThresholdHit.T3_SHORT_STRIKE_BREACH,
                    String.format("IC PE SHORT BREACHED. Spot=%.2f ≤ PE short=%d. Closing all 4 legs. MTM P&L=%s",
                            spot, config.shortLeg().strike(), fmtOrUnknown(mtmPnl)),
                    null, mtmPnl, null, detail);
        }
        if (config.shortLeg2() != null && spot.compareTo(BigDecimal.valueOf(config.shortLeg2().strike())) >= 0) {
            BigDecimal mtmPnl = calcMtmSafe(config, shortLegLtp, longLegLtp);
            return result(MonitorAction.EXIT, ThresholdHit.T3_SHORT_STRIKE_BREACH,
                    String.format("IC CE SHORT BREACHED. Spot=%.2f ≥ CE short=%d. Closing all 4 legs. MTM P&L=%s",
                            spot, config.shortLeg2().strike(), fmtOrUnknown(mtmPnl)),
                    null, mtmPnl, null, detail);
        }

        // ── 3 & 4. P&L thresholds ─────────────────────────────────────────────
        if (shortLegLtp != null && longLegLtp != null) {
            BigDecimal mtmPnl = pnlService.calculateMtmPnl(config, shortLegLtp, longLegLtp);
            detail.put("markToMarketPnl", mtmPnl);

            if (thr.t3LossThreshold() != null
                    && pnlService.hasBreachedLossThreshold(mtmPnl, thr.t3LossThreshold())) {
                return result(MonitorAction.EXIT, ThresholdHit.T3_EXIT_PNL,
                        String.format("IC T3 LOSS STOP. MTM P&L=%.2f breached threshold=%.2f. Closing all 4 legs.",
                                mtmPnl, thr.t3LossThreshold().negate()),
                        null, mtmPnl, null, detail);
            }
            if (thr.t2LossThreshold() != null
                    && pnlService.hasBreachedLossThreshold(mtmPnl, thr.t2LossThreshold())) {
                return result(MonitorAction.READJUST, ThresholdHit.T2_READJUST_PNL,
                        String.format("IC T2 P&L threshold hit. MTM P&L=%.2f breached T2=%.2f.",
                                mtmPnl, thr.t2LossThreshold().negate()),
                        null, mtmPnl, null, detail);
            }
        }

        // ── 5 & 6. Nifty proximity thresholds (bilateral) ─────────────────────
        String pressureSide = detectPressure(thr, spot);
        if (pressureSide != null) {
            boolean isT2 = pressureSide.startsWith("T2");
            MonitorAction action = isT2 ? MonitorAction.READJUST : MonitorAction.WATCH;
            ThresholdHit hit    = isT2 ? ThresholdHit.T2_READJUST_PNL : ThresholdHit.POP_WATCH;
            return result(action, hit,
                    String.format("IC Nifty proximity: %s. Spot=%.2f", pressureSide, spot),
                    null, null, null, detail);
        }

        // ── 7. HOLD ────────────────────────────────────────────────────────────
        return result(MonitorAction.HOLD, ThresholdHit.NONE,
                String.format("IC HOLD. Spot=%.2f within safe zone. PE_short=%s CE_short=%s DTE=%d",
                        spot,
                        config.shortLeg()  != null ? config.shortLeg().strike()  : "n/a",
                        config.shortLeg2() != null ? config.shortLeg2().strike() : "n/a",
                        currentDte),
                null, null, null, detail);
    }

    /**
     * Checks bilateral Nifty proximity zones. Returns a human-readable label of the most
     * severe zone hit, or null if Nifty is safely inside the condor body.
     */
    private String detectPressure(MonitorThresholdsDto thr, BigDecimal spot) {
        boolean peT2 = thr.t2ReadjustNiftyDown() != null && spot.compareTo(thr.t2ReadjustNiftyDown()) <= 0;
        boolean peT1 = thr.t1WatchNiftyDown()    != null && spot.compareTo(thr.t1WatchNiftyDown())    <= 0;
        boolean ceT2 = thr.t2ReadjustNiftyUp()   != null && spot.compareTo(thr.t2ReadjustNiftyUp())   >= 0;
        boolean ceT1 = thr.t1WatchNiftyUp()       != null && spot.compareTo(thr.t1WatchNiftyUp())      >= 0;

        // T2 is more severe than T1; down-side checked before up-side (arbitrary — both escalate)
        if (peT2) return String.format("T2_DOWN: Spot=%.0f ≤ T2=%.0f (PE short at risk)", spot, thr.t2ReadjustNiftyDown());
        if (ceT2) return String.format("T2_UP: Spot=%.0f ≥ T2=%.0f (CE short at risk)",   spot, thr.t2ReadjustNiftyUp());
        if (peT1) return String.format("T1_DOWN: Spot=%.0f ≤ T1=%.0f (watch PE short)",   spot, thr.t1WatchNiftyDown());
        if (ceT1) return String.format("T1_UP: Spot=%.0f ≥ T1=%.0f (watch CE short)",     spot, thr.t1WatchNiftyUp());
        return null;
    }

    private BigDecimal calcMtmSafe(MonitorConfigDto config, BigDecimal shortLtp, BigDecimal longLtp) {
        return (shortLtp != null && longLtp != null)
                ? pnlService.calculateMtmPnl(config, shortLtp, longLtp) : null;
    }

    private String fmtOrUnknown(BigDecimal value) {
        return value != null ? String.format("%.2f", value) : "unknown";
    }

    private EvaluationResult result(MonitorAction action, ThresholdHit hit, String reason,
                                     BigDecimal netPremium, BigDecimal pnl, BigDecimal pop,
                                     Map<String, Object> detail) {
        detail.put("action", action.name());
        detail.put("thresholdHit", hit.name());
        return new EvaluationResult(action, hit, reason, netPremium, pnl, pop, detail);
    }
}
