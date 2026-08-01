package com.the3Cgrp.zupptrade.agent1.explain;

import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignalExplanationServiceTest {

    private final SignalExplanationService svc = new SignalExplanationService();

    private static TierScore tier(String name, String avg) {
        BigDecimal a = new BigDecimal(avg);
        return new TierScore(name, new BigDecimal("0.20"), Map.of(), a, a.multiply(new BigDecimal("0.20")));
    }

    private static Agent1SignalEntity entity(Bias bias, Strength strength, String composite,
                                             Confidence conf, VixRegime regime, String vix, boolean divergence) {
        Agent1SignalEntity e = new Agent1SignalEntity();
        e.setBias(bias);
        e.setStrength(strength);
        e.setCompositeScore(new BigDecimal(composite));
        e.setConfidence(conf);
        e.setVixRegime(regime);
        e.setVixLevel(vix == null ? null : new BigDecimal(vix));
        e.setCommentaryDivergence(divergence);
        return e;
    }

    @Test
    void bullishMild_lowVix_readsNaturally() {
        List<TierScore> tiers = List.of(
                tier("TIER_1A_PRICE_STRUCTURE", "0.5"),
                tier("TIER_1B_TECHNICAL", "0.2"),
                tier("TIER_2_INSTITUTIONAL_FLOW", "0.3"),
                tier("TIER_3_VOLATILITY_MACRO", "0.0"),
                tier("TIER_4_COMMENTARY_SENTIMENT", "0.1"));
        Agent1SignalEntity e = entity(Bias.BULLISH, Strength.MILD, "0.34",
                Confidence.MEDIUM, VixRegime.LOW, "12.8", false);

        String out = svc.build(e, tiers, List.of());

        assertThat(out).contains("Mildly bullish — composite score +0.34");
        assertThat(out).contains("lean bullish");
        assertThat(out).contains("volatility & macro is flat");
        assertThat(out).contains("Confidence is Medium: 4 of 5 tiers agree on direction");
        assertThat(out).contains("VIX 12.8 (Low) is supportive");
    }

    @Test
    void neutralWeak_highVix_withDivergence() {
        List<TierScore> tiers = List.of(
                tier("TIER_1A_PRICE_STRUCTURE", "0.4"),
                tier("TIER_1B_TECHNICAL", "0.0"),
                tier("TIER_2_INSTITUTIONAL_FLOW", "0.05"),
                tier("TIER_3_VOLATILITY_MACRO", "0.0"),
                tier("TIER_4_COMMENTARY_SENTIMENT", "-0.5"));
        Agent1SignalEntity e = entity(Bias.NEUTRAL, Strength.WEAK, "0.07",
                Confidence.LOW, VixRegime.HIGH, "18.61", true);

        String out = svc.build(e, tiers, List.of());

        assertThat(out).contains("Neutral — composite score +0.07, essentially balanced");
        assertThat(out).contains("Confidence is Low");
        assertThat(out).contains("VIX 18.6 (High) trims conviction");
        assertThat(out).contains("Commentary disagrees with the overall read");
    }

    @Test
    void appendsDataGapCaveat() {
        List<TierScore> tiers = List.of(
                tier("TIER_1A_PRICE_STRUCTURE", "-0.4"),
                tier("TIER_2_INSTITUTIONAL_FLOW", "-0.3"));
        Agent1SignalEntity e = entity(Bias.BEARISH, Strength.MILD, "-0.29",
                Confidence.MEDIUM, VixRegime.NORMAL, "15.0", false);

        String out = svc.build(e, tiers, List.of("GIFT_NIFTY", "MARKETAUX"));

        assertThat(out).contains("Mildly bearish — composite score -0.29");
        assertThat(out).contains("2 inputs were unavailable this run (Gift Nifty and news sentiment) and scored neutral");
        // NORMAL VIX adds no conviction clause
        assertThat(out).doesNotContain("trims conviction");
    }

    @Test
    void emptyTiers_stillProducesVerdict() {
        Agent1SignalEntity e = entity(Bias.BULLISH, Strength.EXTREME, "0.62",
                Confidence.HIGH, VixRegime.NORMAL, "16.0", false);

        String out = svc.build(e, List.of(), null);

        assertThat(out).contains("Strongly bullish — composite score +0.62");
    }
}
