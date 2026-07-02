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
 * Tier 1B — Technical Indicators (weight: 20%).
 * Strategy Pattern implementation of TierScorer.
 * 5 signals using TA4J pre-computed indicator values from PrecomputedIndicators.
 */
@Component
public class TechnicalScorer implements TierScorer {

    private static final Logger log = LoggerFactory.getLogger(TechnicalScorer.class);

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

        int vEma2050  = voteEmaCrossover(ind.ema20(),    ind.ema50());
        int vEma50200 = voteEmaCrossover(ind.ema50(),    ind.ema200());
        int vRsi      = voteRsi(ind.rsi14());
        int vMacd     = voteMacd(ind.macdLine(),         ind.macdSignal());
        int vCandle   = voteCandlestick(ind.bullishCandlePattern(), ind.bearishCandlePattern());

        log.info("agent1.tier1b ema20_vs_ema50:  ema20={} ema50={}   → {}", ind.ema20(),    ind.ema50(),    vLabel(vEma2050));
        log.info("agent1.tier1b ema50_vs_ema200: ema50={} ema200={}  → {}", ind.ema50(),    ind.ema200(),   vLabel(vEma50200));
        log.info("agent1.tier1b rsi14:           rsi={}              → {}", ind.rsi14(),                    vLabel(vRsi));
        log.info("agent1.tier1b macd_crossover:  macdLine={} signal={}  → {}", ind.macdLine(), ind.macdSignal(), vLabel(vMacd));
        log.info("agent1.tier1b candlestick:     bullish={} bearish={}  → {}", ind.bullishCandlePattern(), ind.bearishCandlePattern(), vLabel(vCandle));

        Map<String, Integer> signals = new LinkedHashMap<>();
        signals.put("ema20_vs_ema50",   vEma2050);
        signals.put("ema50_vs_ema200",  vEma50200);
        signals.put("rsi14",            vRsi);
        signals.put("macd_crossover",   vMacd);
        signals.put("candlestick",      vCandle);

        return buildTierScore(signals);
    }

    private static String vLabel(int v) {
        return v == 1 ? "+1 (BULLISH)" : v == -1 ? "-1 (BEARISH)" : "0 (NEUTRAL)";
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
