package com.the3Cgrp.zupptrade.agent1.composer;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.PrecomputedIndicators;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that user-profile tier weights personalise the Agent 1 signal.
 *
 * <p>The five tier averages are fixed. With the system config weights (30/20/30/10/10) they
 * net to a NEUTRAL signal; re-weighting to favour the bearish institutional-flow tier flips the
 * SAME averages to BEARISH. Because the composite is computed once from the resolved weights,
 * bias, strength AND confidence all move together — confidence is not held at the system value.
 */
class SignalComposerUserWeightTest {

    // Tier name keys (match TierScorer.getTierName()).
    private static final String T1A = "TIER_1A_PRICE_STRUCTURE";
    private static final String T1B = "TIER_1B_TECHNICAL";
    private static final String T2  = "TIER_2_INSTITUTIONAL_FLOW";
    private static final String T3  = "TIER_3_VOLATILITY_MACRO";
    private static final String T4  = "TIER_4_COMMENTARY_SENTIMENT";

    private TradingProperties props;
    private SignalComposer composer;
    private List<TierScore> tierScores;
    private MarketInputs inputs;

    @BeforeEach
    void setUp() {
        props = new TradingProperties();
        composer = new SignalComposer(props);

        // Fixed averages: price & technicals bullish, institutional flow bearish, rest flat.
        tierScores = List.of(
                tier(T1A, "0.30", "0.50"),
                tier(T1B, "0.20", "0.50"),
                tier(T2,  "0.30", "-0.50"),
                tier(T3,  "0.10", "0.00"),
                tier(T4,  "0.10", "0.00")
        );

        // VIX NORMAL (×1.00) and no ADX (×1.00) so the confidence modifiers are neutral and the
        // label change is driven purely by tier agreement shifting with the re-weighted bias.
        inputs = MarketInputs.builder()
                .vixRegime(VixRegime.NORMAL)
                .indicators(new PrecomputedIndicators(
                        null, null, null, null, null, null, null, false, false, null, null))
                .build();
    }

    /**
     * System weights → composite +0.1000 (NEUTRAL WEAK), 2/5 tiers near-neutral → confidence 0.40 → MEDIUM.
     * User weights favouring T2 (0.60) → composite −0.2000 (BEARISH WEAK), only T2 agrees → 1/5 → 0.20 → LOW.
     */
    @Test
    void userWeightsShiftBiasStrengthAndConfidence() {
        // Baseline: system config weights (null → each tier's own weight).
        Agent1SignalEntity systemSignal = composer.compose(tierScores, inputs, LocalDateTime.now(), null);
        assertThat(systemSignal.getBias()).isEqualTo(Bias.NEUTRAL);
        assertThat(systemSignal.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(systemSignal.getCompositeScore()).isEqualByComparingTo("0.1000");
        assertThat(systemSignal.getConfidence()).isEqualTo(Confidence.MEDIUM);

        // User over-weights the bearish institutional-flow tier (weights still sum to 1.0000).
        Map<String, BigDecimal> userWeights = new LinkedHashMap<>();
        userWeights.put(T1A, new BigDecimal("0.10"));
        userWeights.put(T1B, new BigDecimal("0.10"));
        userWeights.put(T2,  new BigDecimal("0.60"));
        userWeights.put(T3,  new BigDecimal("0.10"));
        userWeights.put(T4,  new BigDecimal("0.10"));

        Agent1SignalEntity userSignal = composer.compose(tierScores, inputs, LocalDateTime.now(), userWeights);

        // Bias and strength follow the user weighting...
        assertThat(userSignal.getBias()).isEqualTo(Bias.BEARISH);
        assertThat(userSignal.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(userSignal.getCompositeScore()).isEqualByComparingTo("-0.2000");
        // ...and so does confidence (MEDIUM → LOW), because it is derived from the same composite.
        assertThat(userSignal.getConfidence())
                .as("confidence must follow the re-weighted bias, not stay at the system value")
                .isEqualTo(Confidence.LOW);
    }

    /** Null and empty weight maps must reproduce the legacy system-weighted signal exactly. */
    @Test
    void nullOrEmptyWeightsFallBackToConfigWeights() {
        Agent1SignalEntity legacy = composer.compose(tierScores, inputs, LocalDateTime.now());
        Agent1SignalEntity nullW  = composer.compose(tierScores, inputs, LocalDateTime.now(), null);
        Agent1SignalEntity emptyW = composer.compose(tierScores, inputs, LocalDateTime.now(), Map.of());

        for (Agent1SignalEntity s : List.of(nullW, emptyW)) {
            assertThat(s.getBias()).isEqualTo(legacy.getBias());
            assertThat(s.getStrength()).isEqualTo(legacy.getStrength());
            assertThat(s.getCompositeScore()).isEqualByComparingTo(legacy.getCompositeScore());
            assertThat(s.getConfidence()).isEqualTo(legacy.getConfidence());
        }
    }

    // --- helpers ---

    private static TierScore tier(String name, String weight, String average) {
        BigDecimal w = new BigDecimal(weight);
        BigDecimal avg = new BigDecimal(average);
        BigDecimal contribution = avg.multiply(w).setScale(4, RoundingMode.HALF_UP);
        return new TierScore(name, w, Map.of(), avg, contribution);
    }
}
