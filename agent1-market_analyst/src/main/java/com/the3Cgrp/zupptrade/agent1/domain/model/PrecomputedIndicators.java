package com.the3Cgrp.zupptrade.agent1.domain.model;

import java.math.BigDecimal;

/**
 * TA4J-computed technical indicator values for the scoring pipeline.
 * Null means insufficient data — scorers treat null as vote 0 (neutral).
 * Strategy Pattern: ScoringPipeline computes once; all TierScorers read from this object.
 */
public record PrecomputedIndicators(
        BigDecimal ema20,        // null if < 20 candles available
        BigDecimal ema50,        // null if < 50 candles available
        BigDecimal ema200,       // null if < 200 candles available
        BigDecimal rsi14,        // null if < 14 candles
        BigDecimal macdLine,     // null if < 35 candles
        BigDecimal macdSignal,   // null if < 35 candles
        BigDecimal adx14,        // null if < 28 candles
        boolean bullishCandlePattern,
        boolean bearishCandlePattern,
        // Higher highs and higher lows over last 3 days (from raw candles)
        Boolean higherHighs,     // null if insufficient candle history
        Boolean higherLows       // null if insufficient candle history
) {
    /** All-null instance used when no candle data is available. */
    public static PrecomputedIndicators empty() {
        return new PrecomputedIndicators(null, null, null, null, null, null, null, false, false, null, null);
    }
}
