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

        // HARD NO_TRADE (VIX EXTREME, or LOW-confidence directional credit): mark skipDecision and
        // run a fallback strategy only so the card shows numbers. The service layer converts
        // skipDecision → REJECTED in production, PENDING_CONFIRM in testing mode (hardGateEnabled=false).
        if (selection.strategy() == Strategy.NO_TRADE || selection.strategy() == Strategy.SKIP) {
            ctx.setSkipDecision(true);
            ctx.setSkipReason(selection.strategy().name());
            selection = selectFallback(bias, strength);
            log.info("layer1.skip.overridden",
                    kv("originalDecision", ctx.getSkipReason()),
                    kv("fallbackStrategy", selection.strategy()),
                    kv("hardGateEnabled", ctx.isHardGateEnabled()));
        } else if (selection.softSkip()) {
            // SOFT skip: the matrix produced a low-conviction / thin-premium strategy (e.g. an Iron
            // Condor into low VIX / non-rich IV). We do NOT force REJECTED — the trade card is built
            // and the RoC/PoP/loss gates + the user make the final call. Only surface a warning.
            ctx.setSoftSkip(true);
            ctx.setSoftSkipReason(selection.softReason());
            log.info("layer1.soft.skip",
                    kv("strategy", selection.strategy()),
                    kv("reason", selection.softReason()),
                    kv("note", "gates_and_user_decide"));
        }

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

    // ── IV drives BUY vs SELL, bias drives WHICH SIDE ────────────────────────────
    // Governing rule (vol-desk logic):
    //   IV RICH  → SELL premium (credit spread) — you are paid well to sell.
    //   IV CHEAP → BUY premium (debit spread)   — options are cheap to own.
    //   IV FAIR  → default to the credit spread (short-DTE theta edge) and let the RoC gate
    //              decide — do NOT hard-skip.
    // A directional lean that is only WEAK is treated as neutral (Iron Condor logic).
    // Nothing here hard-skips: VIX EXTREME (handled in select()) is the only hard stand-aside.

    private StrategySelection selectBullish(Strength strength, VixRegime vixRegime, IvRegime ivRegime) {
        if (strength == Strength.WEAK) {
            // Weak bullish is functionally neutral — mirror the bearish-weak path (Iron Condor logic).
            return selectNeutral(Strength.WEAK, vixRegime, ivRegime);
        }
        if (strength == Strength.EXTREME) {
            // Extreme bullish — go directional (debit) regardless of VIX/IV.
            return new StrategySelection(Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT);
        }
        // MILD bullish, VIX LOW/NORMAL/HIGH (EXTREME already excluded upstream).
        if (vixRegime == VixRegime.LOW || ivRegime == IvRegime.CHEAP) {
            // Cheap premium (low VIX or IV below realised) — buying the move is the better R:R.
            return new StrategySelection(Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT);
        }
        // IV RICH → clean credit; IV FAIR → credit, RoC gate arbitrates.
        return new StrategySelection(Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT);
    }

    private StrategySelection selectBearish(Strength strength, VixRegime vixRegime, IvRegime ivRegime) {
        if (strength == Strength.WEAK) {
            // Weak bearish is functionally neutral — Iron Condor logic (symmetric with bullish-weak).
            return selectNeutral(Strength.WEAK, vixRegime, ivRegime);
        }
        // EXTREME or MILD bearish, VIX LOW/NORMAL/HIGH.
        if (vixRegime == VixRegime.LOW || ivRegime == IvRegime.CHEAP) {
            // Cheap puts — buy the move (debit bear put) rather than sell thin call credit.
            return new StrategySelection(Strategy.BEAR_PUT_SPREAD, SpreadDirection.DEBIT);
        }
        // IV RICH → clean credit; IV FAIR → credit, RoC gate arbitrates.
        return new StrategySelection(Strategy.BEAR_CALL_SPREAD, SpreadDirection.CREDIT);
    }

    private StrategySelection selectNeutral(Strength strength, VixRegime vixRegime, IvRegime ivRegime) {
        // Ideal condor: RICH IV + NORMAL/HIGH VIX — real premium to harvest, non-directional.
        if (ivRegime == IvRegime.RICH && vixRegime == VixRegime.HIGH) {
            return new StrategySelection(Strategy.WIDE_IRON_CONDOR, SpreadDirection.CREDIT);
        }
        if (ivRegime == IvRegime.RICH && vixRegime == VixRegime.NORMAL) {
            return new StrategySelection(Strategy.IRON_CONDOR, SpreadDirection.CREDIT);
        }
        // Thin-premium condor (LOW VIX or IV not rich): still produce a card so the user has the
        // option, but flag it soft — the RoC gate will typically reject when the credit is too thin.
        Strategy ic = vixRegime == VixRegime.HIGH ? Strategy.WIDE_IRON_CONDOR : Strategy.IRON_CONDOR;
        return StrategySelection.soft(ic, SpreadDirection.CREDIT,
                "NEUTRAL_THIN_PREMIUM(vix=" + vixRegime + ",iv=" + ivRegime + ")");
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

    /**
     * Fallback strategy when the decision matrix would return SKIP or NO_TRADE.
     * Provides the most natural credit strategy for the given bias so the full pipeline
     * can run and produce real legs. Used in both modes; production marks the result REJECTED,
     * testing mode marks it PENDING_CONFIRM.
     */
    private StrategySelection selectFallback(Bias bias, Strength strength) {
        return switch (bias) {
            case BULLISH -> strength == Strength.EXTREME
                    ? new StrategySelection(Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT)
                    : new StrategySelection(Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT);
            case BEARISH -> strength == Strength.EXTREME
                    ? new StrategySelection(Strategy.BEAR_PUT_SPREAD, SpreadDirection.DEBIT)
                    : new StrategySelection(Strategy.BEAR_CALL_SPREAD, SpreadDirection.CREDIT);
            case NEUTRAL -> new StrategySelection(Strategy.IRON_CONDOR, SpreadDirection.CREDIT);
        };
    }

    private StrategySelection noTrade() {
        return new StrategySelection(Strategy.NO_TRADE, null);
    }
}
