package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.PrecomputedIndicators;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tier 1A — Price Structure (weight: 30%).
 * Strategy Pattern implementation of TierScorer.
 * 7 signals, each votes +1 / 0 / -1. Average × weight = contribution.
 */
@Component
public class PriceStructureScorer implements TierScorer {

    private final TradingProperties props;

    public PriceStructureScorer(TradingProperties props) {
        this.props = props;
    }

    @Override
    public String getTierName() { return "TIER_1A_PRICE_STRUCTURE"; }

    @Override
    public BigDecimal getWeight() { return props.getScoring().getTier1aWeight(); }

    @Override
    public TierScore calculate(MarketInputs inputs) {
        PrecomputedIndicators ind = inputs.getIndicators();
        BigDecimal spot = inputs.getSpot();

        Map<String, Integer> signals = new LinkedHashMap<>();
        signals.put("spot_vs_ema20",     voteSpotVsEma(spot, ind.ema20()));
        signals.put("spot_vs_ema50",     voteSpotVsEma(spot, ind.ema50()));
        signals.put("spot_vs_ema200",    voteSpotVsEma(spot, ind.ema200()));
        signals.put("higher_highs_lows", voteHigherHighsLows(ind.higherHighs(), ind.higherLows()));
        signals.put("futures_premium",   voteFuturesPremium(inputs.getFuturesPremium()));
        signals.put("pcr",               votePcr(inputs.getPcr()));
        signals.put("max_pain",          voteMaxPain(spot, inputs.getMaxPain()));

        return buildTierScore(signals);
    }

    // --- package-private vote methods — called directly from unit tests ---

    int voteSpotVsEma(BigDecimal spot, BigDecimal ema) {
        if (spot == null || ema == null) return 0;
        return spot.compareTo(ema) > 0 ? 1 : -1;
    }

    int voteHigherHighsLows(Boolean higherHighs, Boolean higherLows) {
        if (higherHighs == null || higherLows == null) return 0;
        if (higherHighs && higherLows) return 1;
        if (!higherHighs && !higherLows) return -1;
        return 0;
    }

    int voteFuturesPremium(BigDecimal premium) {
        if (premium == null) return 0;
        BigDecimal threshold = props.getScoring().getFuturesPremiumThreshold();
        if (premium.compareTo(threshold) > 0) return 1;
        if (premium.negate().compareTo(threshold) > 0) return -1;
        return 0;
    }

    int votePcr(BigDecimal pcr) {
        if (pcr == null) return 0;
        if (pcr.compareTo(props.getPcr().getBullishAbove()) > 0) return 1;
        if (pcr.compareTo(props.getPcr().getBearishBelow()) < 0) return -1;
        return 0;
    }

    int voteMaxPain(BigDecimal spot, Integer maxPain) {
        if (spot == null || maxPain == null) return 0;
        BigDecimal band = props.getScoring().getMaxPainBand();
        BigDecimal mp = BigDecimal.valueOf(maxPain);
        // Spot > maxPain + band → bearish (price stretched above max pain)
        if (spot.compareTo(mp.add(band)) > 0) return -1;
        // Spot < maxPain - band → bullish (price pulled toward max pain from below)
        if (spot.compareTo(mp.subtract(band)) < 0) return 1;
        return 0;
    }

    private TierScore buildTierScore(Map<String, Integer> signals) {
        int sum = signals.values().stream().mapToInt(Integer::intValue).sum();
        BigDecimal average = BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(signals.size()), 4, RoundingMode.HALF_UP);
        BigDecimal contribution = average.multiply(getWeight()).setScale(4, RoundingMode.HALF_UP);
        return new TierScore(getTierName(), getWeight(), signals, average, contribution);
    }
}
