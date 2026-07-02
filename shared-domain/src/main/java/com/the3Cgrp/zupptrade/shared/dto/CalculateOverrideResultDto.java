package com.the3Cgrp.zupptrade.shared.dto;

import java.math.BigDecimal;

/**
 * Result of a /calculate-override call. Nothing is persisted — purely for display.
 * UI uses this to show live PoP / RoC before the user decides to confirm.
 * Instrument keys are included so they can be passed back in OverrideParams at confirm time.
 */
public record CalculateOverrideResultDto(
        // Live LTPs fetched from Upstox option chain for the requested strikes
        BigDecimal peShortLtp,
        BigDecimal peLongLtp,
        BigDecimal ceShortLtp,             // null for 2-leg spreads
        BigDecimal ceLongLtp,

        // Upstox instrument keys — required for order placement
        String peShortInstrumentKey,
        String peLongInstrumentKey,
        String ceShortInstrumentKey,       // null for 2-leg spreads
        String ceLongInstrumentKey,

        BigDecimal netPremiumPerUnit,      // combined (PE + CE for IC)
        BigDecimal pop,                    // seller's PoP on PE short strike, % (0–100)
        BigDecimal maxProfitTotal,
        BigDecimal theoreticalMaxLossTotal,
        BigDecimal realExpectedLossTotal,
        BigDecimal roc,                    // %

        boolean popBlocked,                // true if PoP < 50% — hard block
        boolean lossBlocked                // true if real expected loss > 1.5% of capital — hard block
) {}
