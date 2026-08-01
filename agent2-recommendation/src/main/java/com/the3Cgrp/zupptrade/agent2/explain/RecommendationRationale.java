package com.the3Cgrp.zupptrade.agent2.explain;

import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.core.explain.ExplanationBuilder;
import com.the3Cgrp.zupptrade.core.explain.MarketVocab;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Builds the plain-English "why" for a recommendation, shown behind the recommendation-card help
 * icon (and as the reject reason). Shared prose mechanics come from core-module
 * ({@link ExplanationBuilder} / {@link MarketVocab}); the strategy/leg/gate vocabulary is
 * agent2-specific and lives here.
 *
 * <p>Deterministic and null-safe. Stateless static helper — trivially unit-testable.
 */
public final class RecommendationRationale {

    private RecommendationRationale() {}

    public static String build(RecommendationContext ctx) {
        ExplanationBuilder eb = new ExplanationBuilder();

        // 1. Hard no-trade / pause framing
        if (ctx.isSkipDecision()) {
            if (isVixExtreme(ctx)) {
                return eb.sentence("no trade — with " + vix(ctx)
                        + " the system pauses auto-trading and flags it for manual review").build();
            }
            eb.sentence("this setup would normally be skipped (" + humanSkip(ctx.getSkipReason())
                    + "), shown as a fallback for you to judge");
        } else if (ctx.isSoftSkip()) {
            eb.sentence("low-conviction setup (" + humanSkip(ctx.getSoftSkipReason())
                    + ") — the card is produced, the gates and you decide");
        }

        // 2. Strategy + why chosen
        eb.sentence(strategyClause(ctx));

        // 3. The trade itself
        eb.sentence(legClause(ctx));

        // 4. Gate outcome
        eb.sentence(gateClause(ctx));

        return eb.isEmpty() ? "Strategy: " + strategyName(ctx.getStrategy()) : eb.build();
    }

    // ── Clauses ───────────────────────────────────────────────

    private static String strategyClause(RecommendationContext ctx) {
        String name = strategyName(ctx.getStrategy());
        String bias = MarketVocab.bias(ctx.getEffectiveBias(), ctx.getEffectiveStrength());
        String iv = MarketVocab.iv(ctx.getIvRegime());
        String plan = ctx.getSpreadDirection() == SpreadDirection.DEBIT
                ? "so the plan pays a fixed debit for a directional move"
                : "so the plan collects premium rather than paying for direction";
        return "chosen strategy: " + name + " — the signal is " + bias
                + " with " + iv + " and " + vix(ctx) + ", " + plan;
    }

    private static String legClause(RecommendationContext ctx) {
        TradeLegDto shortLeg = ctx.getShortLeg();
        TradeLegDto longLeg = ctx.getLongLeg();
        if (shortLeg == null || longLeg == null) return null;

        // Iron Condor — two shorts
        if (ctx.getShortLeg2() != null) {
            return "it sells the " + leg(shortLeg) + " and " + leg(ctx.getShortLeg2())
                    + " with protective wings, sized at " + ctx.getLots() + " lots";
        }

        if (ctx.getSpreadDirection() == SpreadDirection.DEBIT) {
            return "it buys the " + leg(longLeg) + " and sells the " + leg(shortLeg)
                    + " to cap the cost, sized at " + ctx.getLots() + " lots";
        }

        // Credit spread — lead with the short and its safety margin
        return "it sells the " + leg(shortLeg) + " (" + sellerPop(shortLeg)
                + delta(shortLeg) + ") and buys the " + leg(longLeg)
                + " as a hedge, sized at " + ctx.getLots() + " lots";
    }

    private static String gateClause(RecommendationContext ctx) {
        List<GateResultDto> gates = ctx.getGateResults();
        if (gates == null || gates.isEmpty()) {
            return ctx.isAllHardGatesPassed() ? "all checks passed" : null;
        }
        if (ctx.isAllHardGatesPassed()) {
            String metrics = passedMetrics(gates);
            return metrics.isEmpty() ? "all checks passed" : "all checks passed: " + metrics;
        }
        // Rejected — name the first failing gate
        GateResultDto failed = gates.stream().filter(g -> !g.passed()).findFirst().orElse(null);
        if (failed == null) return null;
        String detail = "rejected at " + failed.gate() + " — " + failed.description();
        if (failed.value() != null && failed.threshold() != null) {
            detail += " (" + trim(failed.value()) + " vs " + trim(failed.threshold()) + " required)";
        }
        return detail;
    }

    // ── Vocabulary (agent2-specific) ──────────────────────────

    private static String strategyName(Strategy s) {
        if (s == null) return "a spread";
        return switch (s) {
            case BULL_PUT_SPREAD   -> "Bull Put Spread";
            case BEAR_CALL_SPREAD  -> "Bear Call Spread";
            case BULL_CALL_SPREAD  -> "Bull Call Spread";
            case BEAR_PUT_SPREAD   -> "Bear Put Spread";
            case IRON_CONDOR       -> "Iron Condor";
            case WIDE_IRON_CONDOR  -> "Wide Iron Condor";
            case SHORT_STRADDLE    -> "Short Straddle";
            case SKIP, NO_TRADE    -> "No Trade";
        };
    }

    /** e.g. "23750 PE". */
    private static String leg(TradeLegDto leg) {
        return leg.strike() + " " + leg.optionType();
    }

    /**
     * Seller's probability the short option expires worthless. Convention: TradeLegDto.pop() holds
     * Upstox's BUYER PoP (probability ITM), so seller PoP = (1 - pop) × 100.
     */
    private static String sellerPop(TradeLegDto shortLeg) {
        if (shortLeg.pop() == null) return "defined risk";
        BigDecimal sellerPop = BigDecimal.ONE.subtract(shortLeg.pop())
                .multiply(BigDecimal.valueOf(100));
        return "~" + MarketVocab.pct(sellerPop, 0) + " chance of expiring worthless";
    }

    private static String delta(TradeLegDto leg) {
        return leg.delta() == null ? "" : ", delta " + trim(leg.delta());
    }

    private static String passedMetrics(List<GateResultDto> gates) {
        StringBuilder sb = new StringBuilder();
        appendMetric(sb, gates, "G1", "PoP ", "%");
        appendMetric(sb, gates, "G3", "PoP–PoPP gap ", "pp");
        appendMetric(sb, gates, "G4", "RoC ", "%");
        return sb.toString();
    }

    private static void appendMetric(StringBuilder sb, List<GateResultDto> gates,
                                     String code, String label, String unit) {
        gates.stream().filter(g -> code.equals(g.gate()) && g.value() != null).findFirst()
                .ifPresent(g -> {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(label).append(trim(g.value())).append(unit);
                });
    }

    private static boolean isVixExtreme(RecommendationContext ctx) {
        return (ctx.getSignal() != null && ctx.getSignal().getVixRegime() == VixRegime.EXTREME)
                || (ctx.getSkipReason() != null && ctx.getSkipReason().toUpperCase().contains("VIX"));
    }

    private static String vix(RecommendationContext ctx) {
        VixRegime regime = ctx.getSignal() == null ? null : ctx.getSignal().getVixRegime();
        return MarketVocab.vix(regime, ctx.getVix());
    }

    /** Turns tokens like "VIX_EXTREME" / "LOW_CONVICTION" into "vix extreme" / "low conviction". */
    private static String humanSkip(String reason) {
        if (reason == null || reason.isBlank()) return "low conviction";
        return reason.trim().replace('_', ' ').toLowerCase();
    }

    /** Strips trailing zeros so "82.60" → "82.6" and "3.00" → "3". */
    private static String trim(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
