package com.the3Cgrp.zupptrade.agent1.composer;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Converts a list of TierScores into a final Agent1SignalEntity.
 * Template Method consumer — called by ScoringPipeline after all tiers are scored.
 *
 * Formula:
 *   composite = sum(tier.contribution)
 *   base_confidence = tiers_agreeing_with_bias / total_tiers
 *   vix_adjusted = base × vix_modifier × adx_modifier
 *   if Tier4 direction ≠ overall bias → × divergence_penalty (0.80)
 */
@Component
public class SignalComposer {

    private static final BigDecimal BULLISH_EXTREME = new BigDecimal("0.50");
    private static final BigDecimal BULLISH_MILD    = new BigDecimal("0.25");
    private static final BigDecimal BULLISH_WEAK    = new BigDecimal("0.10");
    private static final BigDecimal BEARISH_WEAK    = new BigDecimal("-0.10");
    private static final BigDecimal BEARISH_MILD    = new BigDecimal("-0.25");
    private static final BigDecimal BEARISH_EXTREME = new BigDecimal("-0.50");

    private final TradingProperties props;

    public SignalComposer(TradingProperties props) {
        this.props = props;
    }

    public Agent1SignalEntity compose(List<TierScore> tierScores, MarketInputs inputs, LocalDateTime runTime) {
        BigDecimal composite = computeComposite(tierScores);
        Bias bias = determineActualBias(composite);
        Strength strength = determineStrength(composite);

        BigDecimal baseConfidence = computeBaseConfidence(tierScores, bias);
        BigDecimal vixModifier = vixModifier(inputs.getVixRegime());
        BigDecimal adxModifier = adxModifier(inputs.getIndicators().adx14());

        BigDecimal confidenceScore = baseConfidence.multiply(vixModifier).multiply(adxModifier)
                .setScale(4, RoundingMode.HALF_UP);

        boolean commentaryDivergence = isTier4Diverging(tierScores, bias);
        if (commentaryDivergence) {
            confidenceScore = confidenceScore.multiply(props.getConfidence().getDivergencePenalty())
                    .setScale(4, RoundingMode.HALF_UP);
        }

        // Cap at 1.0
        if (confidenceScore.compareTo(BigDecimal.ONE) > 0) {
            confidenceScore = BigDecimal.ONE;
        }

        Confidence confidenceLabel = toConfidenceLabel(confidenceScore);
        String vixDirection = computeVixDirection(inputs.getVixLevel(), inputs.getVixPrevLevel());

        Agent1SignalEntity entity = new Agent1SignalEntity();
        entity.setTimestamp(runTime);
        entity.setExpiryDate(inputs.getExpiryDate());
        entity.setBias(bias);
        entity.setStrength(strength);
        entity.setCompositeScore(composite.setScale(4, RoundingMode.HALF_UP));
        entity.setConfidenceScore(confidenceScore.setScale(2, RoundingMode.HALF_UP));
        entity.setConfidence(confidenceLabel);
        entity.setVixLevel(inputs.getVixLevel());
        entity.setVixRegime(inputs.getVixRegime());
        entity.setVixDirection(vixDirection);
        entity.setCommentaryDivergence(commentaryDivergence);
        entity.setStatus("ACTIVE");
        return entity;
    }

    // --- package-private for unit tests ---

    BigDecimal computeComposite(List<TierScore> tierScores) {
        return tierScores.stream()
                .map(TierScore::contribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    Bias determineBias(BigDecimal composite) {
        if (composite.compareTo(BigDecimal.ZERO) >= 0) return Bias.BULLISH;
        return Bias.BEARISH;
    }

    Strength determineStrength(BigDecimal composite) {
        BigDecimal abs = composite.abs();
        if (abs.compareTo(BULLISH_EXTREME) > 0) return Strength.EXTREME;
        if (abs.compareTo(BULLISH_MILD) > 0)    return Strength.MILD;
        return Strength.WEAK;
    }

    /**
     * For NEUTRAL band (-0.10 to 0.10), bias is NEUTRAL regardless of sign.
     * We expose the actual bias of the composite for confidence calculation,
     * but set NEUTRAL in the final entity when within the weak band.
     */
    Bias determineActualBias(BigDecimal composite) {
        if (composite.compareTo(BEARISH_WEAK) >= 0 && composite.compareTo(BULLISH_WEAK) <= 0) {
            return Bias.NEUTRAL;
        }
        if (composite.compareTo(BigDecimal.ZERO) > 0) return Bias.BULLISH;
        if (composite.compareTo(BigDecimal.ZERO) < 0) return Bias.BEARISH;
        return Bias.NEUTRAL;
    }

    BigDecimal computeBaseConfidence(List<TierScore> tierScores, Bias overallBias) {
        if (overallBias == Bias.NEUTRAL) {
            // For NEUTRAL: count tiers whose average is also near neutral (< |0.10|)
            long agreeing = tierScores.stream()
                    .filter(t -> t.average().abs().compareTo(BULLISH_WEAK) <= 0)
                    .count();
            return BigDecimal.valueOf(agreeing)
                    .divide(BigDecimal.valueOf(tierScores.size()), 4, RoundingMode.HALF_UP);
        }
        // For directional bias: count tiers whose average sign agrees
        long agreeing = tierScores.stream()
                .filter(t -> tierAgreesWithBias(t.average(), overallBias))
                .count();
        return BigDecimal.valueOf(agreeing)
                .divide(BigDecimal.valueOf(tierScores.size()), 4, RoundingMode.HALF_UP);
    }

    private boolean tierAgreesWithBias(BigDecimal tierAvg, Bias bias) {
        if (bias == Bias.BULLISH) return tierAvg.compareTo(BigDecimal.ZERO) > 0;
        if (bias == Bias.BEARISH) return tierAvg.compareTo(BigDecimal.ZERO) < 0;
        return tierAvg.abs().compareTo(BULLISH_WEAK) <= 0;
    }

    BigDecimal vixModifier(VixRegime regime) {
        if (regime == null) return props.getVixModifier().getNormal();
        return switch (regime) {
            case LOW     -> props.getVixModifier().getLow();
            case NORMAL  -> props.getVixModifier().getNormal();
            case HIGH    -> props.getVixModifier().getHigh();
            case EXTREME -> props.getVixModifier().getExtreme();
        };
    }

    BigDecimal adxModifier(BigDecimal adx) {
        if (adx == null) return props.getAdxModifier().getModerateModifier();
        if (adx.compareTo(props.getAdxModifier().getStrongTrendAbove()) > 0)
            return props.getAdxModifier().getStrongModifier();
        if (adx.compareTo(props.getAdxModifier().getModerateTrendAbove()) > 0)
            return props.getAdxModifier().getModerateModifier();
        return props.getAdxModifier().getWeakModifier();
    }

    boolean isTier4Diverging(List<TierScore> tierScores, Bias overallBias) {
        return tierScores.stream()
                .filter(t -> t.tierName().startsWith("TIER_4"))
                .anyMatch(t -> {
                    BigDecimal avg = t.average();
                    if (overallBias == Bias.BULLISH && avg.compareTo(BigDecimal.ZERO) < 0) return true;
                    if (overallBias == Bias.BEARISH && avg.compareTo(BigDecimal.ZERO) > 0) return true;
                    return false;
                });
    }

    Confidence toConfidenceLabel(BigDecimal score) {
        if (score.compareTo(props.getConfidence().getHighAbove()) > 0) return Confidence.HIGH;
        if (score.compareTo(props.getConfidence().getLowBelow()) >= 0) return Confidence.MEDIUM;
        return Confidence.LOW;
    }

    private String computeVixDirection(BigDecimal vixToday, BigDecimal vixPrev) {
        if (vixToday == null || vixPrev == null) return "Unknown";
        int cmp = vixToday.compareTo(vixPrev);
        if (cmp > 0) return "Rising";
        if (cmp < 0) return "Falling";
        return "Stable";
    }
}
