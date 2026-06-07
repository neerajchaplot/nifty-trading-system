package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalScorerTest {

    private TechnicalScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new TechnicalScorer(new TradingProperties());
    }

    // --- voteEmaCrossover ---

    @Test
    void fastEmaAboveSlow_returnsBullish() {
        assertThat(scorer.voteEmaCrossover(bd("23900"), bd("23690"))).isEqualTo(1);
    }

    @Test
    void fastEmaBelowSlow_returnsBearish() {
        assertThat(scorer.voteEmaCrossover(bd("23690"), bd("23900"))).isEqualTo(-1);
    }

    @Test
    void fastEmaEqualsSlow_returnsBearish() {
        // compareTo > 0 is the only bullish case
        assertThat(scorer.voteEmaCrossover(bd("23900"), bd("23900"))).isEqualTo(-1);
    }

    @Test
    void nullEma_returnsNeutral() {
        assertThat(scorer.voteEmaCrossover(null, bd("23900"))).isEqualTo(0);
        assertThat(scorer.voteEmaCrossover(bd("23900"), null)).isEqualTo(0);
    }

    // --- voteRsi (bullish > 60, bearish < 40) ---

    @Test
    void rsiAbove60_returnsBullish() {
        assertThat(scorer.voteRsi(bd("61"))).isEqualTo(1);
        assertThat(scorer.voteRsi(bd("75"))).isEqualTo(1);
    }

    @Test
    void rsiBelow40_returnsBearish() {
        assertThat(scorer.voteRsi(bd("39"))).isEqualTo(-1);
        assertThat(scorer.voteRsi(bd("25"))).isEqualTo(-1);
    }

    @Test
    void rsiBetween40And60_returnsNeutral() {
        assertThat(scorer.voteRsi(bd("50"))).isEqualTo(0);
        assertThat(scorer.voteRsi(bd("40"))).isEqualTo(0);
        assertThat(scorer.voteRsi(bd("60"))).isEqualTo(0);
    }

    @Test
    void rsiNull_returnsNeutral() {
        assertThat(scorer.voteRsi(null)).isEqualTo(0);
    }

    // --- voteMacd ---

    @Test
    void macdLineAboveSignal_returnsBullish() {
        assertThat(scorer.voteMacd(bd("10"), bd("5"))).isEqualTo(1);
    }

    @Test
    void macdLineBelowSignal_returnsBearish() {
        assertThat(scorer.voteMacd(bd("5"), bd("10"))).isEqualTo(-1);
    }

    @Test
    void macdLineEqualsSignal_returnsNeutral() {
        assertThat(scorer.voteMacd(bd("5"), bd("5"))).isEqualTo(0);
    }

    @Test
    void macdNull_returnsNeutral() {
        assertThat(scorer.voteMacd(null, bd("5"))).isEqualTo(0);
        assertThat(scorer.voteMacd(bd("5"), null)).isEqualTo(0);
    }

    // --- voteCandlestick ---

    @Test
    void bullishPatternOnly_returnsBullish() {
        assertThat(scorer.voteCandlestick(true, false)).isEqualTo(1);
    }

    @Test
    void bearishPatternOnly_returnsBearish() {
        assertThat(scorer.voteCandlestick(false, true)).isEqualTo(-1);
    }

    @Test
    void bothPatterns_returnsNeutral() {
        assertThat(scorer.voteCandlestick(true, true)).isEqualTo(0);
    }

    @Test
    void noPattern_returnsNeutral() {
        assertThat(scorer.voteCandlestick(false, false)).isEqualTo(0);
    }

    private BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
