package com.the3Cgrp.zupptrade.ledger.payload;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Recorded when user manually overrides strikes/lots on a rejected trade and confirms.
 * Stores both the original algorithm recommendation and the user's override side-by-side
 * for audit purposes. Gate rules were bypassed; only PoP≥50% and loss≤1.5% enforced.
 */
public record TradeOverrideConfirmedPayload(
        UUID userProfileId,

        // Original algorithm recommendation
        Object originalLegs,
        BigDecimal originalPop,
        BigDecimal originalRoc,

        // User's override
        Integer peShortStrike,
        Integer peLongStrike,
        Integer ceShortStrike,   // null for 2-leg spreads
        Integer ceLongStrike,    // null for 2-leg spreads
        Integer lots,
        BigDecimal overridePop,
        BigDecimal overrideRoc,
        BigDecimal overrideNetPremiumPerUnit,
        BigDecimal overrideMaxProfitTotal,
        BigDecimal overrideRealExpectedLossTotal
) {}
