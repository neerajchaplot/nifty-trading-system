package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record RecommendationChapter(
        String strategy,
        String spreadDirection,

        // Legs: each map has keys action, strike, type, ltp, iv, delta, theta, vega
        List<Map<String, Object>> legs,

        // Key metrics
        BigDecimal pop,
        BigDecimal popp,
        BigDecimal popGap,
        int lots,
        int lotSize,
        BigDecimal netPremiumPerUnit,
        BigDecimal maxProfitTotal,
        BigDecimal realExpectedLossTotal,
        BigDecimal rocTheoreticalPct,
        BigDecimal rocAnnualised,

        // Gate results: "gate1Pop" → "PASS"/"FAIL"/"INDICATIVE"
        Map<String, String> gateResults,

        // Thresholds
        BigDecimal t1WatchNifty,
        BigDecimal t2ReadjustNifty,
        BigDecimal t2ReadjustPnlLoss,
        BigDecimal t3ExitNifty,
        BigDecimal t3ExitPnlLoss
) {}
