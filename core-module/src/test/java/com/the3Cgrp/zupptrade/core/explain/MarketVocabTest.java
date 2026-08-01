package com.the3Cgrp.zupptrade.core.explain;

import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.IvRegime;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarketVocabTest {

    @Test
    void bias_combinesDirectionAndStrength() {
        assertThat(MarketVocab.bias(Bias.BULLISH, Strength.MILD)).isEqualTo("mildly bullish");
        assertThat(MarketVocab.bias(Bias.BEARISH, Strength.EXTREME)).isEqualTo("strongly bearish");
        assertThat(MarketVocab.bias(Bias.BULLISH, Strength.WEAK)).isEqualTo("marginally bullish");
        assertThat(MarketVocab.bias(Bias.NEUTRAL, Strength.WEAK)).isEqualTo("neutral");
        assertThat(MarketVocab.bias(null, null)).isEqualTo("neutral");
    }

    @Test
    void lean_readsScoreSign() {
        assertThat(MarketVocab.lean(new BigDecimal("0.40"))).isEqualTo("bullish");
        assertThat(MarketVocab.lean(new BigDecimal("-0.10"))).isEqualTo("bearish");
        assertThat(MarketVocab.lean(BigDecimal.ZERO)).isEqualTo("flat");
        assertThat(MarketVocab.lean(null)).isEqualTo("flat");
    }

    @Test
    void vix_formatsLevelAndRegime() {
        assertThat(MarketVocab.vix(VixRegime.HIGH, new BigDecimal("18.61"))).isEqualTo("VIX 18.6 (High)");
        assertThat(MarketVocab.vix(VixRegime.LOW, new BigDecimal("12.8"))).isEqualTo("VIX 12.8 (Low)");
        assertThat(MarketVocab.vix(null, new BigDecimal("15"))).isEqualTo("VIX 15.0");
        assertThat(MarketVocab.vix(VixRegime.EXTREME, null)).isEqualTo("VIX (Extreme)");
    }

    @Test
    void iv_wordsRegime() {
        assertThat(MarketVocab.iv(IvRegime.RICH)).isEqualTo("rich IV");
        assertThat(MarketVocab.iv(IvRegime.CHEAP)).isEqualTo("cheap IV");
        assertThat(MarketVocab.iv(null)).isEqualTo("IV");
    }

    @Test
    void pct_andSignedScore_format() {
        assertThat(MarketVocab.pct(new BigDecimal("82.60"), 1)).isEqualTo("82.6%");
        assertThat(MarketVocab.signedScore(new BigDecimal("0.067"), 2)).isEqualTo("+0.07");
        assertThat(MarketVocab.signedScore(new BigDecimal("-0.29"), 2)).isEqualTo("-0.29");
    }
}
