package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record SignalQualityResponse(
        LocalDate periodFrom,
        LocalDate periodTo,

        int totalSignals,
        int signalsLeadingToTrade,
        int signalsSkipped,

        /** Overall accuracy across all measurable directional signals. */
        BigDecimal overallAccuracyPct,

        /** Win rate broken down by Agent 1 confidence label: HIGH / MEDIUM / LOW → % */
        Map<String, BigDecimal> accuracyByConfidence,

        /** Win rate broken down by signal bias: BULLISH / BEARISH / NEUTRAL → % */
        Map<String, BigDecimal> accuracyByBias,

        /**
         * Impact of commentary divergence on outcomes.
         * Keys: "diverged" (Tier 4 disagreed with overall bias) and "aligned".
         * Values: win rate as %.
         */
        Map<String, BigDecimal> commentaryDivergenceImpact,

        /** The input data gap that was logged most frequently across all scoring runs. */
        String mostFrequentDataGap,

        /** List of skipped signals and why (VIX_EXTREME, WEAK_BIAS, IV_CHEAP, etc.) */
        List<String> skipReasons
) {}
