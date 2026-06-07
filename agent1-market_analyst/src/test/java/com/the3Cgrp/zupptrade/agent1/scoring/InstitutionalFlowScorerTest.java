package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class InstitutionalFlowScorerTest {

    private InstitutionalFlowScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new InstitutionalFlowScorer(new TradingProperties());
    }

    // --- voteFiiNetFutures (threshold = 500 Cr) ---

    @Test
    void fiiNetFuturesBuyAbove500_returnsBullish() {
        assertThat(scorer.voteFiiNetFutures(bd("600"))).isEqualTo(1);
        assertThat(scorer.voteFiiNetFutures(bd("501"))).isEqualTo(1);
    }

    @Test
    void fiiNetFuturesSellAbove500_returnsBearish() {
        assertThat(scorer.voteFiiNetFutures(bd("-600"))).isEqualTo(-1);
        assertThat(scorer.voteFiiNetFutures(bd("-501"))).isEqualTo(-1);
    }

    @Test
    void fiiNetFuturesWithin500_returnsNeutral() {
        assertThat(scorer.voteFiiNetFutures(bd("500"))).isEqualTo(0);
        assertThat(scorer.voteFiiNetFutures(bd("0"))).isEqualTo(0);
        assertThat(scorer.voteFiiNetFutures(bd("-500"))).isEqualTo(0);
    }

    @Test
    void fiiNetFuturesNull_returnsNeutral() {
        assertThat(scorer.voteFiiNetFutures(null)).isEqualTo(0);
    }

    // --- voteFiiLongRatio (bullish > 0.60, bearish < 0.40) ---

    @Test
    void longRatioAbove60pct_returnsBullish() {
        assertThat(scorer.voteFiiLongRatio(bd("0.61"))).isEqualTo(1);
        assertThat(scorer.voteFiiLongRatio(bd("0.80"))).isEqualTo(1);
    }

    @Test
    void longRatioBelow40pct_returnsBearish() {
        assertThat(scorer.voteFiiLongRatio(bd("0.39"))).isEqualTo(-1);
        assertThat(scorer.voteFiiLongRatio(bd("0.11"))).isEqualTo(-1);  // backtest scenario
    }

    @Test
    void longRatioInRange_returnsNeutral() {
        assertThat(scorer.voteFiiLongRatio(bd("0.50"))).isEqualTo(0);
        assertThat(scorer.voteFiiLongRatio(bd("0.40"))).isEqualTo(0);
        assertThat(scorer.voteFiiLongRatio(bd("0.60"))).isEqualTo(0);
    }

    @Test
    void longRatioNull_returnsNeutral() {
        assertThat(scorer.voteFiiLongRatio(null)).isEqualTo(0);
    }

    // --- voteFiiNetOptions (threshold = 500 Cr) ---

    @Test
    void fiiNetOptionsCallBuyerAbove500_returnsBullish() {
        assertThat(scorer.voteFiiNetOptions(bd("600"))).isEqualTo(1);
    }

    @Test
    void fiiNetOptionsPutBuyerAbove500_returnsBearish() {
        assertThat(scorer.voteFiiNetOptions(bd("-600"))).isEqualTo(-1);
    }

    @Test
    void fiiNetOptionsMixed_returnsNeutral() {
        assertThat(scorer.voteFiiNetOptions(bd("400"))).isEqualTo(0);
        assertThat(scorer.voteFiiNetOptions(bd("0"))).isEqualTo(0);
    }

    @Test
    void fiiNetOptionsNull_returnsNeutral() {
        assertThat(scorer.voteFiiNetOptions(null)).isEqualTo(0);
    }

    // --- voteDiiNet (threshold = 500 Cr) ---

    @Test
    void diiNetBuyAbove500_returnsBullish() {
        assertThat(scorer.voteDiiNet(bd("684"))).isEqualTo(1);  // backtest scenario
        assertThat(scorer.voteDiiNet(bd("501"))).isEqualTo(1);
    }

    @Test
    void diiNetSellAbove500_returnsBearish() {
        assertThat(scorer.voteDiiNet(bd("-600"))).isEqualTo(-1);
    }

    @Test
    void diiNetWithin500_returnsNeutral() {
        assertThat(scorer.voteDiiNet(bd("300"))).isEqualTo(0);
        assertThat(scorer.voteDiiNet(bd("500"))).isEqualTo(0);
        assertThat(scorer.voteDiiNet(bd("-500"))).isEqualTo(0);
    }

    @Test
    void diiNetNull_returnsNeutral() {
        assertThat(scorer.voteDiiNet(null)).isEqualTo(0);
    }

    private BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
