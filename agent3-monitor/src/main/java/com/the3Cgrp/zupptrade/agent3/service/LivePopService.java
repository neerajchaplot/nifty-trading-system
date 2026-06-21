package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.math.BlackScholesCalculator;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Recalculates live Black-Scholes PoP for the short option leg.
 * PoP is the primary monitoring decision driver — it naturally integrates
 * current spot distance, live IV (including any VIX spikes), and remaining DTE.
 */
@Service
public class LivePopService {

    private static final Logger log = LoggerFactory.getLogger(LivePopService.class);

    private final BlackScholesCalculator calculator;
    private final MonitoringProperties props;

    public LivePopService(BlackScholesCalculator calculator, MonitoringProperties props) {
        this.calculator = calculator;
        this.props = props;
    }

    /**
     * Computes live PoP for the short leg.
     * Returns null if IV is unavailable — callers treat null PoP as WATCH (conservatively).
     *
     * @param config    trade's monitor config
     * @param spot      current Nifty spot
     * @param shortIv   current IV of the short leg as decimal fraction (e.g. 0.172)
     * @param currentDte days to expiry (may be 0 on expiry day)
     */
    public BigDecimal calculateLivePop(MonitorConfigDto config,
                                        BigDecimal spot,
                                        BigDecimal shortIv,
                                        int currentDte) {
        if (shortIv == null || shortIv.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("agent3.pop.iv_unavailable tradeId={} — using null PoP, will escalate to WATCH",
                    config.tradeId());
            return null;
        }

        BigDecimal shortStrike = BigDecimal.valueOf(config.shortLeg().strike());
        OptionType optionType = config.shortLeg().optionType();

        BigDecimal pop = calculator.calculatePop(
                spot, shortStrike, shortIv, currentDte,
                props.getRiskFreeRate(), optionType);

        log.debug("agent3.pop.calculated tradeId={} spot={} strike={} iv={} dte={} pop={}",
                config.tradeId(), spot, shortStrike, shortIv, currentDte, pop);

        return pop;
    }

    /**
     * Returns true if VIX has spiked > 30% from the reference level.
     * A VIX spike means IV is likely elevated — PoP recalculation (which uses live IV) already
     * incorporates the IV change, so no separate adjustment is needed. We just flag it in the reason.
     */
    public boolean isVixSpike(BigDecimal currentVix, BigDecimal referenceVix) {
        if (currentVix == null || referenceVix == null
                || referenceVix.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal change = currentVix.subtract(referenceVix)
                                      .abs()
                                      .divide(referenceVix, 4, java.math.RoundingMode.HALF_UP);
        return change.compareTo(props.getVixSpikeIntraday()) >= 0;
    }
}
