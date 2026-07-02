package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.PrecomputedIndicators;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(PriceStructureScorer.class);

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

        int vEma20  = voteSpotVsEma(spot, ind.ema20());
        int vEma50  = voteSpotVsEma(spot, ind.ema50());
        int vEma200 = voteSpotVsEma(spot, ind.ema200());
        int vHHL    = voteHigherHighsLows(ind.higherHighs(), ind.higherLows());
        int vFut    = voteFuturesPremium(inputs.getFuturesPremium());
        int vPcr    = votePcr(inputs.getPcr());
        int vMp     = voteMaxPain(spot, inputs.getMaxPain());

        log.info("agent1.tier1a spot_vs_ema20:     spot={} ema20={}   → {}", spot, ind.ema20(),   vLabel(vEma20));
        log.info("agent1.tier1a spot_vs_ema50:     spot={} ema50={}   → {}", spot, ind.ema50(),   vLabel(vEma50));
        log.info("agent1.tier1a spot_vs_ema200:    spot={} ema200={}  → {}", spot, ind.ema200(),  vLabel(vEma200));
        log.info("agent1.tier1a higher_highs_lows: higherHighs={} higherLows={}  → {}", ind.higherHighs(), ind.higherLows(), vLabel(vHHL));
        log.info("agent1.tier1a futures_premium:   premium={}   → {}", inputs.getFuturesPremium(), vLabel(vFut));
        log.info("agent1.tier1a pcr:               pcr={}       → {}", inputs.getPcr(),            vLabel(vPcr));
        log.info("agent1.tier1a max_pain:           spot={} maxPain={}  → {}", spot, inputs.getMaxPain(), vLabel(vMp));

        Map<String, Integer> signals = new LinkedHashMap<>();
        signals.put("spot_vs_ema20",     vEma20);
        signals.put("spot_vs_ema50",     vEma50);
        signals.put("spot_vs_ema200",    vEma200);
        signals.put("higher_highs_lows", vHHL);
        signals.put("futures_premium",   vFut);
        signals.put("pcr",               vPcr);
        signals.put("max_pain",          vMp);

        return buildTierScore(signals);
    }

    private static String vLabel(int v) {
        return v == 1 ? "+1 (BULLISH)" : v == -1 ? "-1 (BEARISH)" : "0 (NEUTRAL)";
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
