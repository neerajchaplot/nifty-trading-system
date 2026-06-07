package com.the3Cgrp.zupptrade.agent2.domain.model;

import java.math.BigDecimal;

public record TradeSummary(
        BigDecimal netPremiumPerUnit,
        int lots,
        int lotSize,
        BigDecimal maxProfitTotal,
        BigDecimal theoreticalMaxLossTotal,
        BigDecimal realExpectedLossTotal,
        BigDecimal pop,
        BigDecimal popp,
        BigDecimal popGap,
        BigDecimal roc,
        BigDecimal rocAnnualised,
        BigDecimal netDelta
) {}
