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
        BigDecimal shortLegIv,
        /** Iron Condor CE-side LTPs (shortLeg2 = CE SELL, longLeg2 = CE BUY). Null for 2-leg spreads. */
        BigDecimal shortLeg2Ltp,
        BigDecimal longLeg2Ltp
) {
    /** 2-leg convenience constructor — CE-side legs are null (bull put / bear call / debit spreads). */
    public LiveMarketSnapshot(BigDecimal spot, BigDecimal vix,
                              BigDecimal shortLegLtp, BigDecimal longLegLtp, BigDecimal shortLegIv) {
        this(spot, vix, shortLegLtp, longLegLtp, shortLegIv, null, null);
    }

    public boolean isComplete() {
        return spot != null && shortLegLtp != null && longLegLtp != null;
    }
}
