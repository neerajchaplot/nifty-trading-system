package com.the3Cgrp.zupptrade.agent2.engine.layer1;

import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.shared.enums.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Layer 1 — Strategy Selection.
 * Maps {bias, strength, vixRegime, ivRegime, confidence} → {strategy, spreadDirection, ivRegime}.
 * Pure deterministic decision matrix — same inputs always produce same output.
 */
@Component
public class StrategySelector {

    private static final Logger log = LoggerFactory.getLogger(StrategySelector.class);

    private final TradingConfig config;

    public StrategySelector(TradingConfig config) {
        this.config = config;
    }

    public void execute(RecommendationContext ctx) {
        Bias bias = ctx.getSignal().getBias();
        Strength strength = ctx.getSignal().getStrength();
        Confidence confidence = ctx.getSignal().getConfidence();
        VixRegime vixRegime = ctx.getSignal().getVixRegime();
        IvRegime ivRegime = resolveIvRegime(ctx);

        ctx.setIvRegime(ivRegime);

        StrategySelection selection = select(bias, strength, vixRegime, ivRegime, confidence);
        ctx.setStrategy(selection.strategy());
        ctx.setSpreadDirection(selection.spreadDirection());

        log.info("layer1.strategy.selected",
                kv("bias", bias),
                kv("strength", strength),
                kv("confidence", confidence),
                kv("vixRegime", vixRegime),
                kv("ivRegime", ivRegime),
                kv("strategy", selection.strategy()),
                kv("spreadDirection", selection.spreadDirection()));
    }

    private StrategySelection select(Bias bias, Strength strength, VixRegime vixRegime,
                                     IvRegime ivRegime, Confidence confidence) {
        if (vixRegime == VixRegime.EXTREME) {
            return noTrade();
        }
        if (confidence == Confidence.LOW) {
            return noTrade();
        }

        return switch (bias) {
            case BULLISH -> selectBullish(strength, vixRegime, ivRegime);
            case BEARISH -> selectBearish(strength, vixRegime, ivRegime);
            case NEUTRAL -> selectNeutral(strength, vixRegime, ivRegime);
        };
    }

    private StrategySelection selectBullish(Strength strength, VixRegime vixRegime, IvRegime ivRegime) {
        if (strength == Strength.EXTREME) {
            // Extreme bullish — go directional regardless of VIX/IV
            return new StrategySelection(Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT);
        }
        if (strength == Strength.MILD
                && (vixRegime == VixRegime.HIGH || vixRegime == VixRegime.NORMAL)
                && ivRegime == IvRegime.RICH) {
            return new StrategySelection(Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT);
        }
        return skip();
    }

    private StrategySelection selectBearish(Strength strength, VixRegime vixRegime, IvRegime ivRegime) {
        if ((strength == Strength.EXTREME || strength == Strength.MILD)
                && (vixRegime == VixRegime.HIGH || vixRegime == VixRegime.NORMAL)
                && ivRegime == IvRegime.RICH) {
            return new StrategySelection(Strategy.BEAR_CALL_SPREAD, SpreadDirection.CREDIT);
        }
        return skip();
    }

    private StrategySelection selectNeutral(Strength strength, VixRegime vixRegime, IvRegime ivRegime) {
        if (strength == Strength.WEAK && ivRegime == IvRegime.RICH) {
            if (vixRegime == VixRegime.HIGH) {
                return new StrategySelection(Strategy.WIDE_IRON_CONDOR, SpreadDirection.CREDIT);
            }
            if (vixRegime == VixRegime.NORMAL) {
                return new StrategySelection(Strategy.IRON_CONDOR, SpreadDirection.CREDIT);
            }
        }
        return skip();
    }

    private IvRegime resolveIvRegime(RecommendationContext ctx) {
        java.math.BigDecimal hv = ctx.getHistoricalVolatility();
        java.math.BigDecimal vix = ctx.getVix();

        if (hv == null || hv.compareTo(java.math.BigDecimal.ZERO) == 0) {
            // HV unavailable — cannot compute ratio; FAIR is the safe default
            log.debug("layer1.ivRegime.hv_unavailable → FAIR");
            return IvRegime.FAIR;
        }
        if (vix == null || vix.compareTo(java.math.BigDecimal.ZERO) == 0) {
            log.debug("layer1.ivRegime.vix_unavailable → FAIR");
            return IvRegime.FAIR;
        }

        // IV = VIX / 100   (VIX is expressed as a percentage, e.g. 15.5 → IV = 0.155)
        // HV is already a decimal (e.g. 0.1540 = 15.40%)
        // ratio > 1.2 → IV is expensive (RICH) → sell premium
        // ratio < 0.85 → IV is cheap (CHEAP) → buy premium
        double iv = vix.doubleValue() / 100.0;
        double hvD = hv.doubleValue();
        double ratio = iv / hvD;

        IvRegime regime;
        if (ratio > config.getIvHvRichThreshold().doubleValue()) {
            regime = IvRegime.RICH;
        } else if (ratio < config.getIvHvCheapThreshold().doubleValue()) {
            regime = IvRegime.CHEAP;
        } else {
            regime = IvRegime.FAIR;
        }

        log.debug("layer1.ivRegime.resolved vix={} iv={} hv={} ratio={} → {}",
                vix, iv, hvD, ratio, regime);
        return regime;
    }

    private StrategySelection noTrade() {
        return new StrategySelection(Strategy.NO_TRADE, null);
    }

    private StrategySelection skip() {
        return new StrategySelection(Strategy.SKIP, null);
    }
}
