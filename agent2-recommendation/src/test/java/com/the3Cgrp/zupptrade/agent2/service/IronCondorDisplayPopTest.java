package com.the3Cgrp.zupptrade.agent2.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Iron Condor DISPLAY PoP shown on the trade card.
 *
 * The condor keeps its premium only if spot finishes BETWEEN both shorts. "Spot below the PE short"
 * and "spot above the CE short" are mutually exclusive tails, so their breach probabilities ADD:
 *     PoP = 1 − peBuyerPop − ceBuyerPop
 *
 * This is display-only — gates (per-side G1/G3) and monitoring (per-side thresholds) are unchanged.
 */
class IronCondorDisplayPopTest {

    @Test
    void combinesBothTails_symmetric15pct() {
        // Each short 15% buyer-ITM (85% seller PoP each). Combined win zone = 1 − 0.15 − 0.15 = 0.70.
        BigDecimal pop = RecommendationService.ironCondorDisplayPopPct(
                new BigDecimal("0.15"), new BigDecimal("0.15"));
        assertThat(pop).isEqualByComparingTo("70.00");
    }

    @Test
    void combinesBothTails_skewed() {
        // PE short close (25% breach), CE short far (2% breach) → 1 − 0.25 − 0.02 = 0.73.
        BigDecimal pop = RecommendationService.ironCondorDisplayPopPct(
                new BigDecimal("0.25"), new BigDecimal("0.02"));
        assertThat(pop).isEqualByComparingTo("73.00");
    }

    @Test
    void isLowerThanEitherSideAlone_neverTheMaxOrAverage() {
        BigDecimal pe = new BigDecimal("0.15");
        BigDecimal ce = new BigDecimal("0.15");
        BigDecimal combined = RecommendationService.ironCondorDisplayPopPct(pe, ce);

        // one-sided (the rejected max() view) would be 85; averaging would also be 85
        BigDecimal oneSided = BigDecimal.ONE.subtract(pe).multiply(BigDecimal.valueOf(100));
        assertThat(combined).isLessThan(oneSided);
        assertThat(combined).isEqualByComparingTo("70.00");
    }

    @Test
    void clampsToZero_whenTailsExceedOne() {
        // Degenerate deep-ITM inputs must never produce a negative probability.
        BigDecimal pop = RecommendationService.ironCondorDisplayPopPct(
                new BigDecimal("0.70"), new BigDecimal("0.60"));
        assertThat(pop).isEqualByComparingTo("0.00");
    }

    @Test
    void clampsToHundred_andHandlesNulls() {
        assertThat(RecommendationService.ironCondorDisplayPopPct(null, null))
                .isEqualByComparingTo("100.00");
        assertThat(RecommendationService.ironCondorDisplayPopPct(new BigDecimal("0.10"), null))
                .isEqualByComparingTo("90.00");
    }
}
