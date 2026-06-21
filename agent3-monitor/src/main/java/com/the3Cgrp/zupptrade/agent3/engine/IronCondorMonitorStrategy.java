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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Monitors IRON_CONDOR positions.
 *
 * Iron Condor = short put spread + short call spread combined.
 *
 * MonitorConfigDto carries one pair of legs (shortLeg, longLeg). For Iron Condor:
 *   - shortLeg = the threatened side's short strike (whichever is ITM/near-the-money)
 *   - MonitorThresholdsDto stores the more conservative (nearest short strike) Nifty levels
 *   - Combined P&L uses the full position P&L stored in thresholds
 *
 * TODO(agent3): Full Iron Condor requires 4-leg data — add putSideShortLeg, putSideLongLeg,
 *   callSideShortLeg, callSideLongLeg to MonitorConfigDto in shared-domain, and fetch
 *   live LTPs for all 4 legs. Per-side PoP comparison (put side vs call side) to determine
 *   which side is under pressure. Currently monitored as single-side using thresholds.
 *
 * v1 approach: treat Iron Condor as a credit spread on the threatened side.
 * The thresholds stored by Agent 2 already reflect the most conservative levels.
 */
@Component
public class IronCondorMonitorStrategy implements MonitorStrategy {

    private static final Logger log = LoggerFactory.getLogger(IronCondorMonitorStrategy.class);

    private final PnlCalculationService pnlService;
    private final LivePopService livePopService;
    private final CreditSpreadMonitorStrategy creditSpreadDelegate;
    private final MonitoringProperties props;

    public IronCondorMonitorStrategy(PnlCalculationService pnlService,
                                      LivePopService livePopService,
                                      CreditSpreadMonitorStrategy creditSpreadDelegate,
                                      MonitoringProperties props) {
        this.pnlService = pnlService;
        this.livePopService = livePopService;
        this.creditSpreadDelegate = creditSpreadDelegate;
        this.props = props;
    }

    @Override
    public Strategy getStrategy() {
        return Strategy.IRON_CONDOR;
    }

    @Override
    public EvaluationResult evaluate(MonitorEvaluationContext context) {
        log.debug("agent3.iron_condor.evaluate tradeId={} — delegating to credit spread logic on threatened side. " +
                  "TODO: implement full 4-leg Iron Condor monitoring with per-side PoP comparison.",
                context.config().tradeId());

        // Delegate to credit spread logic for the single-side (threatened side) stored in monitorConfig.
        // The combined P&L thresholds from Agent 2 ensure exit happens at the correct level.
        EvaluationResult delegateResult = creditSpreadDelegate.evaluate(context);

        // Prepend Iron Condor context to reason
        String icReason = "[IRON_CONDOR — monitoring threatened side only. TODO: full 4-leg support] "
                + delegateResult.reason();

        return new EvaluationResult(
                delegateResult.action(),
                delegateResult.thresholdHit(),
                icReason,
                delegateResult.currentNetPremium(),
                delegateResult.markToMarketPnl(),
                delegateResult.livePop(),
                addIronCondorNote(delegateResult.detail())
        );
    }

    private Map<String, Object> addIronCondorNote(Map<String, Object> detail) {
        Map<String, Object> enriched = new HashMap<>(detail);
        enriched.put("ironCondorNote", "v1: single-side monitoring only — 4-leg support pending");
        return enriched;
    }
}
