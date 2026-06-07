package com.the3Cgrp.zupptrade.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeCardDto(
        UUID tradeId,
        Strategy strategy,
        SpreadDirection spreadDirection,
        LocalDate expiryDate,
        int dte,
        TradeLegDto shortLeg,
        TradeLegDto longLeg,
        BigDecimal netPremiumPerUnit,
        int lots,
        int lotSize,
        BigDecimal maxProfitTotal,
        BigDecimal theoreticalMaxLossTotal,
        BigDecimal realExpectedLossTotal,  // 50% of theoretical — Agent 3 T2 exit assumption
        BigDecimal pop,
        BigDecimal popp,
        BigDecimal popGap,                 // pop - popp, must be <= 15%
        BigDecimal roc,                    // return on capital %
        BigDecimal rocAnnualised,
        BigDecimal netDelta,
        List<GateResultDto> gateResults,
        MonitorThresholdsDto thresholds,
        String rationale,
        LocalDateTime generatedAt,
        LocalDateTime validUntil,          // generatedAt + 20 min
        TradeStatus status
) {}
