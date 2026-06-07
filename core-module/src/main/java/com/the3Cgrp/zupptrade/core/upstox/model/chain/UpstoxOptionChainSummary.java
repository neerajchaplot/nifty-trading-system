package com.the3Cgrp.zupptrade.core.upstox.model.chain;

import java.math.BigDecimal;

/**
 * Computed summary derived from the raw option chain response.
 * All fields may be null if the underlying data was unavailable.
 *
 * vixPrevLevel:    null — agent1 supplies it from the previously stored signal in DB.
 * futuresPremium:  not included here — computed in the agent1 wrapper (UpstoxOptionChainClient)
 *                  by fetching futures LTP via UpstoxMarketQuoteClient and subtracting spot.
 */
public record UpstoxOptionChainSummary(
        BigDecimal spot,
        BigDecimal pcr,           // sum(put OI) / sum(call OI) across all strikes
        Integer    maxPain,       // strike with minimum total payout to option buyers
        BigDecimal vixLevel,      // current India VIX from market-quote/ltp
        BigDecimal callOiChange,  // sum(oi - prevOi) for all calls
        BigDecimal putOiChange    // sum(oi - prevOi) for all puts
) {}
