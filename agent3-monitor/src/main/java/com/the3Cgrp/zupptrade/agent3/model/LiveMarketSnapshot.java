package com.the3Cgrp.zupptrade.agent3.model;

import java.math.BigDecimal;

/**
 * Live market data fetched from Upstox at the time of evaluation.
 * All fields nullable — a missing value means the data was unavailable (market closed, API error).
 * The evaluation strategies handle nulls gracefully: unavailable PoP → WATCH.
 */
public record LiveMarketSnapshot(
        BigDecimal spot,
        BigDecimal vix,
        BigDecimal shortLegLtp,
        BigDecimal longLegLtp,
        /** Short leg IV as decimal (e.g. 0.172 = 17.2%). Null if option chain unavailable. */
        BigDecimal shortLegIv
) {
    public boolean isComplete() {
        return spot != null && shortLegLtp != null && longLegLtp != null;
    }
}
