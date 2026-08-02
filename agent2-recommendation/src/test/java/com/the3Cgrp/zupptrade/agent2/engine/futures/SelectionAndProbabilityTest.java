package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmProbability;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmSelection;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.OpenZone;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Arm selection (§2.4) and probability ranking (§2.5).
 */
class SelectionAndProbabilityTest {

    private final ArmSelector selector = new ArmSelector();
    private final ProbabilityRanker ranker = new ProbabilityRanker();

    @Test
    void selection_biasThenOpenLocation_collapsesToOnePrimary() {
        assertThat(selector.select(Bias.BULLISH, OpenZone.RANGE).primary())
                .isEqualTo(FutureArmType.LONG_ROTATION);
        assertThat(selector.select(Bias.BULLISH, OpenZone.BREAKOUT).primary())
                .isEqualTo(FutureArmType.LONG_BREAKOUT);
        assertThat(selector.select(Bias.BEARISH, OpenZone.RANGE).primary())
                .isEqualTo(FutureArmType.SHORT_ROTATION);
        assertThat(selector.select(Bias.BEARISH, OpenZone.BREAKDOWN).primary())
                .isEqualTo(FutureArmType.SHORT_BREAKDOWN);
    }

    @Test
    void selection_biasVsOpenConflict_isNoTrade() {
        assertThat(selector.select(Bias.BULLISH, OpenZone.BREAKDOWN).noTrade()).isTrue();
        assertThat(selector.select(Bias.BEARISH, OpenZone.BREAKOUT).noTrade()).isTrue();
    }

    @Test
    void selection_neutral_isRangeFadeBothRotationArms() {
        ArmSelection sel = selector.select(Bias.NEUTRAL, OpenZone.RANGE);
        assertThat(sel.rangeFade()).isTrue();
        assertThat(sel.primary()).isNull();
        assertThat(sel.alternatives())
                .containsExactlyInAnyOrder(FutureArmType.LONG_ROTATION, FutureArmType.SHORT_ROTATION);
    }

    @Test
    void probability_bullishHighConf_ranksPrimaryHighest_floorsOpposers() {
        ArmSelection sel = selector.select(Bias.BULLISH, OpenZone.RANGE); // primary LONG_ROTATION
        List<ArmProbability> ranked = ranker.rank(sel, Bias.BULLISH, new BigDecimal("0.80"));

        // Sums to ~100 and every arm is floored at ≥ ~8%.
        BigDecimal sum = ranked.stream().map(ArmProbability::probabilityPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum.doubleValue()).isCloseTo(100.0, within(0.2));
        assertThat(ranked).allSatisfy(a ->
                assertThat(a.probabilityPct().doubleValue()).isGreaterThanOrEqualTo(7.9));

        ArmProbability top = ranked.stream()
                .max(Comparator.comparing(ArmProbability::probabilityPct)).orElseThrow();
        assertThat(top.type()).isEqualTo(FutureArmType.LONG_ROTATION);

        // The two long arms (aligned) outrank the two short arms (opposed).
        BigDecimal longRot = pct(ranked, FutureArmType.LONG_ROTATION);
        BigDecimal shortRot = pct(ranked, FutureArmType.SHORT_ROTATION);
        assertThat(longRot).isGreaterThan(shortRot);
    }

    private BigDecimal pct(List<ArmProbability> ranked, FutureArmType type) {
        return ranked.stream().filter(a -> a.type() == type).findFirst().orElseThrow().probabilityPct();
    }
}
