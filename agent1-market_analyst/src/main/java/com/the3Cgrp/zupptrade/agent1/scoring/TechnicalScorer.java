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
 * Tier 1B — Technical Indicators (weight: 20%).
 * Strategy Pattern implementation of TierScorer.
 * 5 signals using TA4J pre-computed indicator values from PrecomputedIndicators.
 */
@Component
public class TechnicalScorer implements TierScorer {

    private final TradingProperties props;

    public TechnicalScorer(TradingProperties props) {
        this.props = props;
    }

    @Override
    public String getTierName() { return "TIER_1B_TECHNICAL"; }

    @Override
    public BigDecimal getWeight() { return props.getScoring().getTier1bWeight(); }

    @Override
    public TierScore calculate(MarketInputs inputs) {
        PrecomputedIndicators ind = inputs.getIndicators();

        Map<String, Integer> signals = new LinkedHashMap<>();
        signals.put("ema20_vs_ema50",   voteEmaCrossover(ind.ema20(), ind.ema50()));
        signals.put("ema50_vs_ema200",  voteEmaCrossover(ind.ema50(), ind.ema200()));
        signals.put("rsi14",            voteRsi(ind.rsi14()));
        signals.put("macd_crossover",   voteMacd(ind.macdLine(), ind.macdSignal()));
        signals.put("candlestick",      voteCandlestick(ind.bullishCandlePattern(), ind.bearishCandlePattern()));

        return buildTierScore(signals);
    }

    // --- package-private vote methods ---

    int voteEmaCrossover(BigDecimal fastEma, BigDecimal slowEma) {
        if (fastEma == null || slowEma == null) return 0;
        return fastEma.compareTo(slowEma) > 0 ? 1 : -1;
    }

    int voteRsi(BigDecimal rsi) {
        if (rsi == null) return 0;
        if (rsi.compareTo(props.getScoring().getRsiOverbought()) > 0) return 1;
        if (rsi.compareTo(props.getScoring().getRsiOversold()) < 0) return -1;
        return 0;
    }

    int voteMacd(BigDecimal macdLine, BigDecimal macdSignal) {
        if (macdLine == null || macdSignal == null) return 0;
        int cmp = macdLine.compareTo(macdSignal);
        if (cmp > 0) return 1;
        if (cmp < 0) return -1;
        return 0;
    }

    int voteCandlestick(boolean bullish, boolean bearish) {
        if (bullish && !bearish) return 1;
        if (bearish && !bullish) return -1;
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
