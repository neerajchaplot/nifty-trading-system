package com.the3Cgrp.zupptrade.agent1.composer;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignalComposerTest {

    private SignalComposer composer;

    @BeforeEach
    void setUp() {
        composer = new SignalComposer(new TradingProperties());
    }

    // --- computeComposite ---

    @Test
    void compositeIsSumOfContributions() {
        List<TierScore> scores = List.of(
                tier("T1A", "0.30", "0.10", "0.03"),
                tier("T1B", "0.20", "0.05", "0.01"),
                tier("T2",  "0.30", "-0.20", "-0.06")
        );
        BigDecimal result = composer.computeComposite(scores);
        assertThat(result).isEqualByComparingTo("-0.02");
    }

    // --- determineActualBias ---

    @Test
    void positiveAbove10_returnsBullish() {
        assertThat(composer.determineActualBias(bd("0.15"))).isEqualTo(Bias.BULLISH);
        assertThat(composer.determineActualBias(bd("0.50"))).isEqualTo(Bias.BULLISH);
    }

    @Test
    void negativeBelow10_returnsBearish() {
        assertThat(composer.determineActualBias(bd("-0.15"))).isEqualTo(Bias.BEARISH);
        assertThat(composer.determineActualBias(bd("-0.50"))).isEqualTo(Bias.BEARISH);
    }

    @Test
    void withinNeutralBand_returnsNeutral() {
        assertThat(composer.determineActualBias(bd("0.09"))).isEqualTo(Bias.NEUTRAL);
        assertThat(composer.determineActualBias(bd("-0.09"))).isEqualTo(Bias.NEUTRAL);
        assertThat(composer.determineActualBias(bd("0.00"))).isEqualTo(Bias.NEUTRAL);
        assertThat(composer.determineActualBias(bd("0.10"))).isEqualTo(Bias.NEUTRAL);
        assertThat(composer.determineActualBias(bd("-0.10"))).isEqualTo(Bias.NEUTRAL);
    }

    // --- determineStrength ---

    @Test
    void absAbove50_returnsExtreme() {
        assertThat(composer.determineStrength(bd("0.55"))).isEqualTo(Strength.EXTREME);
        assertThat(composer.determineStrength(bd("-0.55"))).isEqualTo(Strength.EXTREME);
    }

    @Test
    void absBetween25And50_returnsMild() {
        assertThat(composer.determineStrength(bd("0.30"))).isEqualTo(Strength.MILD);
        assertThat(composer.determineStrength(bd("-0.30"))).isEqualTo(Strength.MILD);
    }

    @Test
    void absUpTo25_returnsWeak() {
        assertThat(composer.determineStrength(bd("0.05"))).isEqualTo(Strength.WEAK);
        assertThat(composer.determineStrength(bd("-0.05"))).isEqualTo(Strength.WEAK);
        assertThat(composer.determineStrength(bd("0.25"))).isEqualTo(Strength.WEAK);
    }

    // --- vixModifier ---

    @Test
    void vixModifierLow_returns110() {
        assertThat(composer.vixModifier(VixRegime.LOW)).isEqualByComparingTo("1.10");
    }

    @Test
    void vixModifierNormal_returns100() {
        assertThat(composer.vixModifier(VixRegime.NORMAL)).isEqualByComparingTo("1.00");
    }

    @Test
    void vixModifierHigh_returns085() {
        assertThat(composer.vixModifier(VixRegime.HIGH)).isEqualByComparingTo("0.85");
    }

    @Test
    void vixModifierExtreme_returns060() {
        assertThat(composer.vixModifier(VixRegime.EXTREME)).isEqualByComparingTo("0.60");
    }

    @Test
    void vixModifierNull_returnsNormalModifier() {
        assertThat(composer.vixModifier(null)).isEqualByComparingTo("1.00");
    }

    // --- adxModifier ---

    @Test
    void adxAbove30_returnsStrongModifier() {
        assertThat(composer.adxModifier(bd("31"))).isEqualByComparingTo("1.15");
        assertThat(composer.adxModifier(bd("50"))).isEqualByComparingTo("1.15");
    }

    @Test
    void adxBetween20And30_returnsModerateModifier() {
        assertThat(composer.adxModifier(bd("25"))).isEqualByComparingTo("1.00");
        assertThat(composer.adxModifier(bd("21"))).isEqualByComparingTo("1.00");
    }

    @Test
    void adxBelow20_returnsWeakModifier() {
        assertThat(composer.adxModifier(bd("15"))).isEqualByComparingTo("0.80");
        assertThat(composer.adxModifier(bd("10"))).isEqualByComparingTo("0.80");
    }

    @Test
    void adxNull_returnsModerateModifier() {
        assertThat(composer.adxModifier(null)).isEqualByComparingTo("1.00");
    }

    // --- toConfidenceLabel ---

    @Test
    void scoreAbove070_returnsHigh() {
        assertThat(composer.toConfidenceLabel(bd("0.71"))).isEqualTo(Confidence.HIGH);
        assertThat(composer.toConfidenceLabel(bd("1.00"))).isEqualTo(Confidence.HIGH);
    }

    @Test
    void scoreBetween041And070_returnsMedium() {
        assertThat(composer.toConfidenceLabel(bd("0.41"))).isEqualTo(Confidence.MEDIUM);
        assertThat(composer.toConfidenceLabel(bd("0.55"))).isEqualTo(Confidence.MEDIUM);
        assertThat(composer.toConfidenceLabel(bd("0.70"))).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void scoreBelow041_returnsLow() {
        assertThat(composer.toConfidenceLabel(bd("0.40"))).isEqualTo(Confidence.LOW);
        assertThat(composer.toConfidenceLabel(bd("0.20"))).isEqualTo(Confidence.LOW);
        assertThat(composer.toConfidenceLabel(bd("0.00"))).isEqualTo(Confidence.LOW);
    }

    // --- computeBaseConfidence ---

    @Test
    void allTiersAgreeBullish_returnsFullConfidence() {
        List<TierScore> scores = List.of(
                tier("T1A", "0.30", "0.50", "0.15"),
                tier("T1B", "0.20", "0.40", "0.08"),
                tier("T2",  "0.30", "0.30", "0.09"),
                tier("T3",  "0.10", "0.20", "0.02"),
                tier("T4",  "0.10", "0.10", "0.01")
        );
        BigDecimal conf = composer.computeBaseConfidence(scores, Bias.BULLISH);
        assertThat(conf).isEqualByComparingTo("1.0000");
    }

    @Test
    void noTiersAgreeBullish_returnsZeroConfidence() {
        List<TierScore> scores = List.of(
                tier("T1A", "0.30", "-0.50", "-0.15"),
                tier("T1B", "0.20", "-0.40", "-0.08")
        );
        BigDecimal conf = composer.computeBaseConfidence(scores, Bias.BULLISH);
        assertThat(conf).isEqualByComparingTo("0.0000");
    }

    // --- isTier4Diverging ---

    @Test
    void tier4BearishWhenOverallBullish_isDiverging() {
        List<TierScore> scores = List.of(
                tier("TIER_4_COMMENTARY_SENTIMENT", "0.10", "-0.50", "-0.05"),
                tier("TIER_1A_PRICE_STRUCTURE",     "0.30",  "0.40",  "0.12")
        );
        assertThat(composer.isTier4Diverging(scores, Bias.BULLISH)).isTrue();
    }

    @Test
    void tier4BullishWhenOverallBullish_isNotDiverging() {
        List<TierScore> scores = List.of(
                tier("TIER_4_COMMENTARY_SENTIMENT", "0.10", "0.50", "0.05"),
                tier("TIER_1A_PRICE_STRUCTURE",     "0.30", "0.40", "0.12")
        );
        assertThat(composer.isTier4Diverging(scores, Bias.BULLISH)).isFalse();
    }

    @Test
    void noTier4Present_isNotDiverging() {
        List<TierScore> scores = List.of(
                tier("TIER_1A_PRICE_STRUCTURE", "0.30", "0.40", "0.12")
        );
        assertThat(composer.isTier4Diverging(scores, Bias.BULLISH)).isFalse();
    }

    private TierScore tier(String name, String weight, String average, String contribution) {
        return new TierScore(name, bd(weight), Map.of(), bd(average), bd(contribution));
    }

    private BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
