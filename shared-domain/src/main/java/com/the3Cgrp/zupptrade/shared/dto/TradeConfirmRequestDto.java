package com.the3Cgrp.zupptrade.shared.dto;

import com.the3Cgrp.zupptrade.shared.enums.ConfirmAction;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TradeConfirmRequestDto(
        @NotNull UUID tradeId,
        @NotNull ConfirmAction action,
        Integer            overrideLots,        // optional — user may adjust lots before confirming
        OverrideThresholds overrideThresholds,  // optional — user may adjust T1/T2/T3 monitoring levels
        OverrideParams     overrideParams       // set when user manually overrides strikes; bypasses gate rules
) {
    /**
     * Optional T1/T2/T3 Nifty level overrides.
     * Only non-null fields replace the algorithm-computed defaults.
     * The backend validates PoP constraints (T3 must not breach the short strike)
     * and ordering before persisting.
     */
    public record OverrideThresholds(
            Integer t1WatchNiftyLevel,    // null = keep default
            Integer t2ReadjustNiftyLevel, // null = keep default
            Integer t3ExitNiftyLevel      // null = keep default; must be ≥ PE short strike for credit spreads
    ) {}

    /**
     * Provided when the user manually chose different strikes/lots from the rejected trade card.
     * Backend uses these to update the trade entity legs + summary before confirming.
     * Pre-validated: PoP ≥ 50% and real expected loss ≤ 1.5% of capital (enforced by /calculate-override).
     */
    public record OverrideParams(
            Integer    peShortStrike,
            Integer    peLongStrike,
            Integer    ceShortStrike,             // null for 2-leg spreads
            Integer    ceLongStrike,
            Integer    lots,
            BigDecimal peShortLtp,
            BigDecimal peLongLtp,
            BigDecimal ceShortLtp,
            BigDecimal ceLongLtp,
            String     peShortInstrumentKey,      // Upstox instrument key — required for order placement
            String     peLongInstrumentKey,
            String     ceShortInstrumentKey,      // null for 2-leg spreads
            String     ceLongInstrumentKey,
            BigDecimal netPremiumPerUnit,
            BigDecimal pop,
            BigDecimal maxProfitTotal,
            BigDecimal theoreticalMaxLossTotal,
            BigDecimal realExpectedLossTotal,
            BigDecimal roc
    ) {}
}
