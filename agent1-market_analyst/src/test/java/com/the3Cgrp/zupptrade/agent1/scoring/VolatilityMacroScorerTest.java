package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VolatilityMacroScorerTest {

    private VolatilityMacroScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new VolatilityMacroScorer(new TradingProperties());
    }

    // --- voteVixChange (bullish if daily change < -2%, bearish if > +10%) ---

    @Test
    void vixFellMoreThan2pct_returnsBullish() {
        // (18.61 - 19.43) / 19.43 × 100 ≈ -4.22% — backtest scenario
        assertThat(scorer.voteVixChange(bd("18.61"), bd("19.43"))).isEqualTo(1);
        // exactly -2.01%
        assertThat(scorer.voteVixChange(bd("17.99"), bd("18.37"))).isEqualTo(1);
    }

    @Test
    void vixRoseMoreThan10pct_returnsBearish() {
        // +11% change
        assertThat(scorer.voteVixChange(bd("22.21"), bd("20.00"))).isEqualTo(-1);
    }

    @Test
    void vixChangeWithinBand_returnsNeutral() {
        // -1% change (not bullish)
        assertThat(scorer.voteVixChange(bd("19.80"), bd("20.00"))).isEqualTo(0);
        // +5% change (not bearish)
        assertThat(scorer.voteVixChange(bd("21.00"), bd("20.00"))).isEqualTo(0);
    }

    @Test
    void vixNull_returnsNeutral() {
        assertThat(scorer.voteVixChange(null, bd("20.00"))).isEqualTo(0);
        assertThat(scorer.voteVixChange(bd("20.00"), null)).isEqualTo(0);
    }

    @Test
    void vixPrevZero_returnsNeutral() {
        assertThat(scorer.voteVixChange(bd("20.00"), bd("0"))).isEqualTo(0);
    }

    // --- voteOiChange ---

    @Test
    void morePutOiBuilding_returnsBullish() {
        assertThat(scorer.voteOiChange(bd("1000"), bd("1500"))).isEqualTo(1);
    }

    @Test
    void moreCallOiBuilding_returnsBearish() {
        assertThat(scorer.voteOiChange(bd("1500"), bd("1000"))).isEqualTo(-1);
    }

    @Test
    void equalOiChange_returnsNeutral() {
        assertThat(scorer.voteOiChange(bd("1000"), bd("1000"))).isEqualTo(0);
    }

    @Test
    void oiChangeNull_returnsNeutral() {
        assertThat(scorer.voteOiChange(null, bd("1000"))).isEqualTo(0);
        assertThat(scorer.voteOiChange(bd("1000"), null)).isEqualTo(0);
    }

    // --- voteGiftNifty (significant threshold = 50 pts) ---

    @Test
    void giftNiftyAbove50_returnsBullish() {
        assertThat(scorer.voteGiftNifty(bd("70"))).isEqualTo(1);  // backtest scenario
        assertThat(scorer.voteGiftNifty(bd("51"))).isEqualTo(1);
    }

    @Test
    void giftNiftyDiscountAbove50_returnsBearish() {
        assertThat(scorer.voteGiftNifty(bd("-70"))).isEqualTo(-1);
        assertThat(scorer.voteGiftNifty(bd("-51"))).isEqualTo(-1);
    }

    @Test
    void giftNiftyWithin50_returnsNeutral() {
        assertThat(scorer.voteGiftNifty(bd("50"))).isEqualTo(0);
        assertThat(scorer.voteGiftNifty(bd("0"))).isEqualTo(0);
        assertThat(scorer.voteGiftNifty(bd("-50"))).isEqualTo(0);
    }

    @Test
    void giftNiftyNull_returnsNeutral() {
        assertThat(scorer.voteGiftNifty(null)).isEqualTo(0);
    }

    private BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
