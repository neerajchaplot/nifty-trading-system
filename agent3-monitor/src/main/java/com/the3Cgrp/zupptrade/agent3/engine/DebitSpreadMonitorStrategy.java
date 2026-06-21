package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
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
 * Monitors BULL_CALL_SPREAD and BEAR_PUT_SPREAD (debit spreads).
 *
 * Decision priority:
 *   1. VIX Extreme (>24)                         → PAUSE
 *   2. Theta exit: DTE ≤ 2 with no profit        → EXIT (debit spreads decay rapidly near expiry)
 *   3. T3: MTM loss ≥ 50% of premium paid        → EXIT
 *   4. T2 Profit: spot reaches 1% RoC target      → EXIT for profit
 *   5. T1 Profit: spot reaches 0.5% RoC target    → WATCH (trail stop, book profit soon)
 *   6. Default                                    → HOLD
 */
@Component
public class DebitSpreadMonitorStrategy implements MonitorStrategy {

    private final PnlCalculationService pnlService;
    private final MonitoringProperties props;

    public DebitSpreadMonitorStrategy(PnlCalculationService pnlService,
                                       MonitoringProperties props) {
        this.pnlService = pnlService;
        this.props = props;
    }

    @Override
    public Strategy getStrategy() {
        return Strategy.BULL_CALL_SPREAD;
    }

    @Override
    public EvaluationResult evaluate(MonitorEvaluationContext context) {
        MonitorConfigDto config  = context.config();
        MonitorThresholdsDto thr = config.thresholds();
        BigDecimal spot          = context.liveData().spot();
        BigDecimal vix           = context.liveData().vix();
        BigDecimal shortLegLtp   = context.liveData().shortLegLtp();
        BigDecimal longLegLtp    = context.liveData().longLegLtp();
        int currentDte           = context.currentDte();

        Map<String, Object> detail = new HashMap<>();
        detail.put("spot", spot);
        detail.put("vix", vix);
        detail.put("currentDte", currentDte);
        detail.put("shortLegLtp", shortLegLtp);
        detail.put("longLegLtp", longLegLtp);

        // --- 1. VIX Extreme ---
        if (vix != null && vix.compareTo(props.getVixExtremeThreshold()) > 0) {
            return result(MonitorAction.PAUSE, ThresholdHit.VIX_EXTREME_PAUSE,
                    String.format("VIX=%.2f exceeds extreme threshold (%.0f) — auto-trading paused.",
                            vix, props.getVixExtremeThreshold()),
                    null, null, detail);
        }

        // --- 2. Theta exit ---
        if (currentDte <= props.getDebitThetaExitDte()) {
            BigDecimal mtmPnl = (spot != null && shortLegLtp != null && longLegLtp != null)
                    ? pnlService.calculateMtmPnl(config, shortLegLtp, longLegLtp) : null;
            // Only exit on theta if not already profitable (T1/T2 profit targets checked below)
            boolean notProfitable = mtmPnl == null || mtmPnl.compareTo(BigDecimal.ZERO) <= 0;
            if (notProfitable) {
                return result(MonitorAction.EXIT, ThresholdHit.DEBIT_THETA_EXIT,
                        String.format("DTE=%d ≤ theta exit threshold (%d). Debit spread losing to time decay " +
                                "with no move to profit target. Closing to recover remaining value. MTM P&L=%s",
                                currentDte, props.getDebitThetaExitDte(),
                                mtmPnl != null ? String.format("%.2f", mtmPnl) : "unknown"),
                        null, mtmPnl, detail);
            }
        }

        if (spot == null || shortLegLtp == null || longLegLtp == null) {
            return result(MonitorAction.WATCH, ThresholdHit.NONE,
                    "Market data unavailable — holding conservatively at WATCH.",
                    null, null, detail);
        }

        BigDecimal currentNetPremium = pnlService.currentNetPremium(
                config.spreadDirection(), shortLegLtp, longLegLtp);
        BigDecimal mtmPnl = pnlService.calculateMtmPnl(config, shortLegLtp, longLegLtp);
        detail.put("currentNetPremium", currentNetPremium);
        detail.put("markToMarketPnl", mtmPnl);

        // --- 3. T3: loss stop (50% of premium paid) ---
        if (thr.t2LossThreshold() != null
                && pnlService.hasBreachedLossThreshold(mtmPnl, thr.t2LossThreshold())) {
            return result(MonitorAction.EXIT, ThresholdHit.DEBIT_T3_LOSS_PNL,
                    String.format("T3 LOSS STOP: MTM P&L=%.2f — 50%% of premium paid is lost. Closing all legs.",
                            mtmPnl),
                    currentNetPremium, mtmPnl, detail);
        }

        // --- 4. T2 Profit: 1% RoC target reached ---
        if (thr.t2ReadjustNiftyLevel() != null && hasProfitTargetBeenHit(config, spot, thr.t2ReadjustNiftyLevel())) {
            return result(MonitorAction.EXIT, ThresholdHit.DEBIT_T2_PROFIT_NIFTY,
                    String.format("T2 PROFIT TARGET hit. Spot=%.2f reached 1%% RoC level=%.0f. " +
                            "Closing all legs to lock profit. MTM P&L=%.2f",
                            spot, thr.t2ReadjustNiftyLevel().doubleValue(), mtmPnl),
                    currentNetPremium, mtmPnl, detail);
        }

        // --- 5. T1 Profit: 0.5% RoC target reached ---
        if (thr.t1WatchNiftyLevel() != null && hasProfitTargetBeenHit(config, spot, thr.t1WatchNiftyLevel())) {
            return result(MonitorAction.WATCH, ThresholdHit.DEBIT_T1_PROFIT_NIFTY,
                    String.format("T1 PROFIT LEVEL reached. Spot=%.2f at 0.5%% RoC level=%.0f. " +
                            "Monitoring closely — consider booking profit. MTM P&L=%.2f",
                            spot, thr.t1WatchNiftyLevel().doubleValue(), mtmPnl),
                    currentNetPremium, mtmPnl, detail);
        }

        // --- 6. HOLD ---
        return result(MonitorAction.HOLD, ThresholdHit.NONE,
                String.format("Waiting for directional move. Spot=%.2f, DTE=%d. MTM P&L=%.2f. " +
                        "Profit target=%.0f.",
                        spot, currentDte, mtmPnl,
                        thr.t1WatchNiftyLevel() != null ? thr.t1WatchNiftyLevel().doubleValue() : 0),
                currentNetPremium, mtmPnl, detail);
    }

    /** True when spot has moved to or past the profit target level for this spread direction. */
    private boolean hasProfitTargetBeenHit(MonitorConfigDto config, BigDecimal spot, BigDecimal targetLevel) {
        OptionType longLegType = config.longLeg().optionType();
        return switch (longLegType) {
            // Bull Call: long call — profit when spot rises to target
            case CE -> spot.compareTo(targetLevel) >= 0;
            // Bear Put: long put — profit when spot falls to target
            case PE -> spot.compareTo(targetLevel) <= 0;
        };
    }

    private EvaluationResult result(MonitorAction action, ThresholdHit hit, String reason,
                                     BigDecimal netPremium, BigDecimal pnl,
                                     Map<String, Object> detail) {
        detail.put("action", action.name());
        detail.put("thresholdHit", hit.name());
        return new EvaluationResult(action, hit, reason, netPremium, pnl, null, detail);
    }
}
