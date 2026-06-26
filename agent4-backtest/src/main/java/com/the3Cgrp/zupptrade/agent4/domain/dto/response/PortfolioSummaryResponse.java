package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record PortfolioSummaryResponse(

        // Period
        LocalDate periodFrom,
        LocalDate periodTo,

        // Volume
        int totalTrades,
        int winCount,
        int lossCount,

        // Core profitability
        BigDecimal winRatePct,
        BigDecimal totalRealizedPnl,
        BigDecimal maxLossSingleTrade,
        BigDecimal maxDrawdown,

        // Return on capital
        BigDecimal avgRocAchievedPct,
        BigDecimal avgRocTheoreticalPct,
        BigDecimal rocCaptureRatioPct,

        // Agility
        int totalAdjustments,
        BigDecimal adjustmentRecoveryRatePct,

        // Signal quality
        BigDecimal agent1AccuracyPct,

        // Breakdowns
        Map<String, Long> strategyMix,
        Map<String, BigDecimal> winRateByVixRegime,
        Map<String, BigDecimal> winRateByConfidence
) {}
