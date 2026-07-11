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
        // Use user-weight-recomputed values if present; fall back to signal values.
        Bias bias       = ctx.getEffectiveBias()     != null ? ctx.getEffectiveBias()     : ctx.getSignal().getBias();
        Strength strength = ctx.getEffectiveStrength() != null ? ctx.getEffectiveStrength() : ctx.getSignal().getStrength();
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

        StrategySelection candidate = selectByBias(bias, strength, vixRegime, ivRegime);

        // Confidence.LOW blocks directional credit strategies at Layer 1.
        // Non-directional strategies (IronCondor, WideIronCondor) are exempt — they profit
        // from sideways movement and don't require directional conviction, so low confidence
        // on the direction is irrelevant to their thesis.
        // Debit strategies are allowed through — Gate 3D rejects LOW confidence with a
        // more informative reason tied to the quality of directional evidence.
        boolean isNonDirectional = candidate.strategy() == Strategy.IRON_CONDOR
                || candidate.strategy() == Strategy.WIDE_IRON_CONDOR;
        if (confidence == Confidence.LOW && !candidate.strategy().isDebit() && !isNonDirectional) {
            return noTrade();
        }

        return candidate;
    }

    private StrategySelection selectByBias(Bias bias, Strength strength,
                                           VixRegime vixRegime, IvRegime ivRegime) {
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
        if (strength == Strength.MILD && vixRegime == VixRegime.LOW) {
            // Low VIX = cheap premiums — debit is better R:R than selling thin credit
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
                && vixRegime == VixRegime.LOW) {
            // Low VIX = cheap puts — debit bear put spread is better than selling thin call credit
            return new StrategySelection(Strategy.BEAR_PUT_SPREAD, SpreadDirection.DEBIT);
        }
        if ((strength == Strength.EXTREME || strength == Strength.MILD)
                && (vixRegime == VixRegime.HIGH || vixRegime == VixRegime.NORMAL)
                && ivRegime == IvRegime.RICH) {
            return new StrategySelection(Strategy.BEAR_CALL_SPREAD, SpreadDirection.CREDIT);
        }
        // WEAK bearish is functionally neutral — reuse neutral logic (IronCondor/WideIronCondor)
        // rather than skipping. Directional conviction is absent but IV richness can still be harvested.
        if (strength == Strength.WEAK) {
            return selectNeutral(Strength.WEAK, vixRegime, ivRegime);
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

        if (hv == null || hv.compareTo(java.math.BigDecimal.ZERO) == 0) {
            log.debug("layer1.ivRegime.hv_unavailable → FAIR");
            return IvRegime.FAIR;
        }

        // Prefer ATM option IV from the already-fetched option chain.
        // VIX is a 30-day blended index; the actual ATM IV for the specific expiry
        // (especially near-term) can differ materially and is the correct richness measure.
        // Fall back to VIX / 100 only when chain data is absent.
        java.math.BigDecimal iv = resolveIv(ctx);
        if (iv == null || iv.compareTo(java.math.BigDecimal.ZERO) == 0) {
            log.debug("layer1.ivRegime.iv_unavailable → FAIR");
            return IvRegime.FAIR;
        }

        String ivSource = (ctx.getOptionChainData() != null) ? "atm_chain" : "vix_proxy";
        double ratio = iv.doubleValue() / hv.doubleValue();

        IvRegime regime;
        if (ratio > config.getIvHvRichThreshold().doubleValue()) {
            regime = IvRegime.RICH;
        } else if (ratio < config.getIvHvCheapThreshold().doubleValue()) {
            regime = IvRegime.CHEAP;
        } else {
            regime = IvRegime.FAIR;
        }

        log.debug("layer1.ivRegime.resolved iv_source={} vix={} iv={} hv={} ratio={} → {}",
                ivSource, ctx.getVix(), iv, hv, ratio, regime);
        return regime;
    }

    /**
     * Resolves the implied volatility to use for IV/HV ratio calculation.
     * Reads ATM call IV from the option chain (the market's actual reading for this expiry).
     * Falls back to VIX / 100 when the chain is unavailable or the ATM IV is zero/null.
     */
    private java.math.BigDecimal resolveIv(RecommendationContext ctx) {
        com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData chain = ctx.getOptionChainData();
        if (chain != null && chain.calls() != null) {
            java.math.BigDecimal atmIv = chain.calls().stream()
                    .filter(s -> s.strike() == chain.atmStrike())
                    .findFirst()
                    .map(s -> s.iv())
                    .filter(v -> v != null && v.compareTo(java.math.BigDecimal.ZERO) > 0)
                    .orElse(null);
            if (atmIv != null) {
                return atmIv;
            }
        }
        // Fallback: VIX / 100 (e.g. VIX 15.5 → IV 0.155)
        java.math.BigDecimal vix = ctx.getVix();
        if (vix != null && vix.compareTo(java.math.BigDecimal.ZERO) > 0) {
            return vix.divide(java.math.BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP);
        }
        return null;
    }

    private StrategySelection noTrade() {
        return new StrategySelection(Strategy.NO_TRADE, null);
    }

    private StrategySelection skip() {
        return new StrategySelection(Strategy.SKIP, null);
    }
}
