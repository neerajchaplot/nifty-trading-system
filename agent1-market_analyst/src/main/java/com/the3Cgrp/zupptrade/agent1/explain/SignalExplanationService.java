package com.the3Cgrp.zupptrade.agent1.explain;

import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import com.the3Cgrp.zupptrade.core.explain.ExplanationBuilder;
import com.the3Cgrp.zupptrade.core.explain.MarketVocab;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the plain-English "why" behind a scored signal, shown behind the market-strip help icon.
 *
 * <p>Deterministic and best-effort: the shared prose mechanics live in core-module
 * ({@link ExplanationBuilder} / {@link MarketVocab}); only the agent1-specific vocabulary
 * (tier names, data-gap names) lives here. Never throws — a failure degrades to a one-line
 * fallback so it can never block the scoring pipeline.
 */
@Service
public class SignalExplanationService {

    private static final Logger log = LoggerFactory.getLogger(SignalExplanationService.class);

    /** Same neutral band the composer uses to decide a tier "agrees" with a NEUTRAL read. */
    private static final BigDecimal NEUTRAL_BAND = new BigDecimal("0.10");

    /** Friendly names for the tier tokens emitted by the scorers. */
    private static String tierLabel(String tierName) {
        if (tierName == null) return "a factor";
        if (tierName.startsWith("TIER_1A")) return "price structure";
        if (tierName.startsWith("TIER_1B")) return "technicals";
        if (tierName.startsWith("TIER_2"))  return "institutional flow";
        if (tierName.startsWith("TIER_3"))  return "volatility & macro";
        if (tierName.startsWith("TIER_4"))  return "news & commentary";
        return tierName.toLowerCase();
    }

    /** Friendly names for the data-gap tokens produced by the pipeline. */
    private static String gapLabel(String token) {
        return switch (token) {
            case "SPOT"        -> "spot";
            case "VIX"         -> "VIX";
            case "PCR"         -> "PCR";
            case "FII_FUTURES" -> "FII futures flow";
            case "FII_OPTIONS" -> "FII options flow";
            case "DII"         -> "DII flow";
            case "GIFT_NIFTY"  -> "Gift Nifty";
            case "MARKETAUX"   -> "news sentiment";
            default            -> token == null ? "an input" : token.toLowerCase();
        };
    }

    /**
     * @param signal     the composed entity (bias/strength/composite/confidence/vix/divergence)
     * @param tierScores the per-tier results (for lean grouping + agreement count)
     * @param dataGaps   inputs unavailable this run (may be null/empty)
     */
    public String build(Agent1SignalEntity signal, List<TierScore> tierScores, List<String> dataGaps) {
        try {
            return buildInternal(signal, tierScores, dataGaps);
        } catch (Exception e) {
            log.warn("signal.explanation.failed — using fallback: {}", e.getMessage());
            return fallback(signal);
        }
    }

    private String buildInternal(Agent1SignalEntity s, List<TierScore> tierScores, List<String> dataGaps) {
        Bias bias = s.getBias();
        boolean neutral = bias == Bias.NEUTRAL;

        ExplanationBuilder eb = new ExplanationBuilder();

        // 1. Verdict + composite
        String verdict = MarketVocab.bias(bias, s.getStrength())
                + " — composite score " + MarketVocab.signedScore(s.getCompositeScore(), 2)
                + (neutral ? ", essentially balanced" : "");
        eb.sentence(verdict);

        // 2. Which tiers drove it
        eb.sentence(driverClause(tierScores));

        // 3. Confidence
        eb.sentence(confidenceClause(s, tierScores, bias));

        // 4. Caveats
        eb.sentenceIf(Boolean.TRUE.equals(s.getCommentaryDivergence()),
                "commentary disagrees with the overall read");
        if (dataGaps != null && !dataGaps.isEmpty()) {
            List<String> friendly = dataGaps.stream().map(SignalExplanationService::gapLabel).toList();
            String noun = dataGaps.size() == 1 ? "input was" : "inputs were";
            eb.sentence("note: " + dataGaps.size() + " " + noun + " unavailable this run ("
                    + joinAnd(friendly) + ") and scored neutral");
        }

        return eb.isEmpty() ? fallback(s) : eb.build();
    }

    private String driverClause(List<TierScore> tierScores) {
        if (tierScores == null || tierScores.isEmpty()) return null;

        // Group tier labels by lean, preserving tier order.
        Map<String, List<String>> byLean = new LinkedHashMap<>();
        byLean.put("bullish", new ArrayList<>());
        byLean.put("bearish", new ArrayList<>());
        byLean.put("flat", new ArrayList<>());
        for (TierScore t : tierScores) {
            byLean.get(MarketVocab.lean(t.average())).add(tierLabel(t.tierName()));
        }

        List<String> parts = new ArrayList<>();
        List<String> bullish = byLean.get("bullish");
        List<String> bearish = byLean.get("bearish");
        List<String> flat    = byLean.get("flat");
        if (!bullish.isEmpty()) parts.add(joinAnd(bullish) + verb(bullish.size()) + " bullish");
        if (!bearish.isEmpty()) parts.add(joinAnd(bearish) + verb(bearish.size()) + " bearish");
        if (!flat.isEmpty())    parts.add(joinAnd(flat) + (flat.size() == 1 ? " is" : " are") + " flat");
        return String.join(", ", parts);
    }

    private String confidenceClause(Agent1SignalEntity s, List<TierScore> tierScores, Bias bias) {
        int total = tierScores == null ? 0 : tierScores.size();
        int agreeing = countAgreeing(tierScores, bias);
        String label = titleCase(s.getConfidence() == null ? "" : s.getConfidence().name());

        StringBuilder c = new StringBuilder("confidence is ").append(label);
        if (total > 0) {
            c.append(": ").append(agreeing).append(" of ").append(total).append(" tiers agree on direction");
        }
        String vixEffect = vixEffect(s.getVixRegime(), s.getVixLevel());
        if (vixEffect != null) c.append(", ").append(vixEffect);
        return c.toString();
    }

    private String vixEffect(VixRegime regime, BigDecimal level) {
        if (regime == null) return null;
        String vix = MarketVocab.vix(regime, level);
        return switch (regime) {
            case LOW              -> "and " + vix + " is supportive";
            case HIGH, EXTREME    -> "and " + vix + " trims conviction";
            case NORMAL           -> null;
        };
    }

    private int countAgreeing(List<TierScore> tierScores, Bias bias) {
        if (tierScores == null) return 0;
        return (int) tierScores.stream().filter(t -> {
            BigDecimal avg = t.average();
            if (bias == Bias.BULLISH) return avg.signum() > 0;
            if (bias == Bias.BEARISH) return avg.signum() < 0;
            return avg.abs().compareTo(NEUTRAL_BAND) <= 0; // NEUTRAL
        }).count();
    }

    private String fallback(Agent1SignalEntity s) {
        return MarketVocab.bias(s.getBias(), s.getStrength())
                + " — composite score " + MarketVocab.signedScore(s.getCompositeScore(), 2) + ".";
    }

    private static String verb(int count) {
        return count == 1 ? " leans" : " lean";
    }

    /** ["a"] → "a"; ["a","b"] → "a and b"; ["a","b","c"] → "a, b and c". */
    private static String joinAnd(List<String> items) {
        int n = items.size();
        if (n == 0) return "";
        if (n == 1) return items.get(0);
        if (n == 2) return items.get(0) + " and " + items.get(1);
        return String.join(", ", items.subList(0, n - 1)) + " and " + items.get(n - 1);
    }

    private static String titleCase(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "";
        return enumName.charAt(0) + enumName.substring(1).toLowerCase();
    }
}
