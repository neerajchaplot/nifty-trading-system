package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PriceStructureScorerTest {

    private PriceStructureScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new PriceStructureScorer(new TradingProperties());
    }

    // --- voteSpotVsEma ---

    @Test
    void spotAboveEma_returnsBullish() {
        assertThat(scorer.voteSpotVsEma(bd("24000"), bd("23500"))).isEqualTo(1);
    }

    @Test
    void spotBelowEma_returnsBearish() {
        assertThat(scorer.voteSpotVsEma(bd("23000"), bd("23500"))).isEqualTo(-1);
    }

    @Test
    void spotEqualsEma_returnsBearish() {
        // compareTo > 0 is the bullish condition; equal → -1
        assertThat(scorer.voteSpotVsEma(bd("23500"), bd("23500"))).isEqualTo(-1);
    }

    @Test
    void spotNull_returnsNeutral() {
        assertThat(scorer.voteSpotVsEma(null, bd("23500"))).isEqualTo(0);
    }

    @Test
    void emaNull_returnsNeutral() {
        assertThat(scorer.voteSpotVsEma(bd("23500"), null)).isEqualTo(0);
    }

    // --- voteHigherHighsLows ---

    @Test
    void higherHighsAndHigherLows_returnsBullish() {
        assertThat(scorer.voteHigherHighsLows(true, true)).isEqualTo(1);
    }

    @Test
    void lowerHighsAndLowerLows_returnsBearish() {
        assertThat(scorer.voteHigherHighsLows(false, false)).isEqualTo(-1);
    }

    @Test
    void mixedPattern_returnsNeutral() {
        assertThat(scorer.voteHigherHighsLows(true, false)).isEqualTo(0);
        assertThat(scorer.voteHigherHighsLows(false, true)).isEqualTo(0);
    }

    @Test
    void nullHigherHighsLows_returnsNeutral() {
        assertThat(scorer.voteHigherHighsLows(null, null)).isEqualTo(0);
        assertThat(scorer.voteHigherHighsLows(true, null)).isEqualTo(0);
    }

    // --- voteFuturesPremium (threshold = 20) ---

    @Test
    void premiumAbove20_returnsBullish() {
        assertThat(scorer.voteFuturesPremium(bd("21"))).isEqualTo(1);
        assertThat(scorer.voteFuturesPremium(bd("50"))).isEqualTo(1);
    }

    @Test
    void discountAbove20_returnsBearish() {
        assertThat(scorer.voteFuturesPremium(bd("-21"))).isEqualTo(-1);
        assertThat(scorer.voteFuturesPremium(bd("-50"))).isEqualTo(-1);
    }

    @Test
    void premiumWithin20_returnsNeutral() {
        assertThat(scorer.voteFuturesPremium(bd("20"))).isEqualTo(0);
        assertThat(scorer.voteFuturesPremium(bd("0"))).isEqualTo(0);
        assertThat(scorer.voteFuturesPremium(bd("-20"))).isEqualTo(0);
    }

    @Test
    void premiumNull_returnsNeutral() {
        assertThat(scorer.voteFuturesPremium(null)).isEqualTo(0);
    }

    // --- votePcr (bullish > 1.20, bearish < 0.80) ---

    @Test
    void pcrAbove120_returnsBullish() {
        assertThat(scorer.votePcr(bd("1.21"))).isEqualTo(1);
        assertThat(scorer.votePcr(bd("1.50"))).isEqualTo(1);
    }

    @Test
    void pcrBelow80_returnsBearish() {
        assertThat(scorer.votePcr(bd("0.79"))).isEqualTo(-1);
        assertThat(scorer.votePcr(bd("0.50"))).isEqualTo(-1);
    }

    @Test
    void pcrInRange_returnsNeutral() {
        assertThat(scorer.votePcr(bd("1.17"))).isEqualTo(0);
        assertThat(scorer.votePcr(bd("1.00"))).isEqualTo(0);
        assertThat(scorer.votePcr(bd("0.80"))).isEqualTo(0);
        assertThat(scorer.votePcr(bd("1.20"))).isEqualTo(0);
    }

    @Test
    void pcrNull_returnsNeutral() {
        assertThat(scorer.votePcr(null)).isEqualTo(0);
    }

    // --- voteMaxPain (band = 100) ---

    @Test
    void spotBelowMaxPainMinus100_returnsBullish() {
        // spot < maxPain - 100 → bullish (price pulled up toward max pain)
        assertThat(scorer.voteMaxPain(bd("23400"), 23600)).isEqualTo(1);
        assertThat(scorer.voteMaxPain(bd("23000"), 23500)).isEqualTo(1);
    }

    @Test
    void spotAboveMaxPainPlus100_returnsBearish() {
        // spot > maxPain + 100 → bearish
        assertThat(scorer.voteMaxPain(bd("24000"), 23800)).isEqualTo(-1);
    }

    @Test
    void spotWithinBand_returnsNeutral() {
        assertThat(scorer.voteMaxPain(bd("23500"), 23500)).isEqualTo(0);
        assertThat(scorer.voteMaxPain(bd("23600"), 23500)).isEqualTo(0);  // 23600 > 23500+100? No, 23500+100=23600, not >
        assertThat(scorer.voteMaxPain(bd("23450"), 23500)).isEqualTo(0);
    }

    @Test
    void spotOrMaxPainNull_returnsNeutral() {
        assertThat(scorer.voteMaxPain(null, 23500)).isEqualTo(0);
        assertThat(scorer.voteMaxPain(bd("23500"), null)).isEqualTo(0);
    }

    private BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
