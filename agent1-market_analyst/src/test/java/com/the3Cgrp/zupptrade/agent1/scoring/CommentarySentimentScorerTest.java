package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CommentarySentimentScorerTest {

    private CommentarySentimentScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new CommentarySentimentScorer(new TradingProperties());
    }

    // --- voteMarketaux (bullish > 0.30, bearish < -0.30) ---

    @Test
    void sentimentAbove030_returnsBullish() {
        assertThat(scorer.voteMarketaux(bd("0.31"))).isEqualTo(1);
        assertThat(scorer.voteMarketaux(bd("0.80"))).isEqualTo(1);
    }

    @Test
    void sentimentBelow030Negative_returnsBearish() {
        assertThat(scorer.voteMarketaux(bd("-0.31"))).isEqualTo(-1);
        assertThat(scorer.voteMarketaux(bd("-0.335"))).isEqualTo(-1);  // backtest scenario
    }

    @Test
    void sentimentInRange_returnsNeutral() {
        assertThat(scorer.voteMarketaux(bd("0.30"))).isEqualTo(0);
        assertThat(scorer.voteMarketaux(bd("0.00"))).isEqualTo(0);
        assertThat(scorer.voteMarketaux(bd("-0.30"))).isEqualTo(0);
        assertThat(scorer.voteMarketaux(bd("0.15"))).isEqualTo(0);
    }

    @Test
    void sentimentNull_returnsNeutral() {
        assertThat(scorer.voteMarketaux(null)).isEqualTo(0);
    }

    // --- voteCommentary ---

    @Test
    void bullishCommentary_returnsBullish() {
        assertThat(scorer.voteCommentary("bullish")).isEqualTo(1);
        assertThat(scorer.voteCommentary("BULLISH")).isEqualTo(1);
        assertThat(scorer.voteCommentary("  Bullish  ")).isEqualTo(1);
    }

    @Test
    void bearishCommentary_returnsBearish() {
        assertThat(scorer.voteCommentary("bearish")).isEqualTo(-1);
        assertThat(scorer.voteCommentary("BEARISH")).isEqualTo(-1);
    }

    @Test
    void neutralOrUnknownCommentary_returnsNeutral() {
        assertThat(scorer.voteCommentary("neutral")).isEqualTo(0);
        assertThat(scorer.voteCommentary("range-bound")).isEqualTo(0);
        assertThat(scorer.voteCommentary("mixed")).isEqualTo(0);
    }

    @Test
    void nullCommentary_returnsNeutral() {
        assertThat(scorer.voteCommentary(null)).isEqualTo(0);
    }

    private BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
