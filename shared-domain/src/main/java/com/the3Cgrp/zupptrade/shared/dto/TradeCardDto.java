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
        TradeLegDto shortLeg2,              // IC only: CE SELL leg (null for 2-leg spreads)
        TradeLegDto longLeg2,               // IC only: CE BUY leg (null for 2-leg spreads)
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
        TradeStatus status,
        boolean testingModeActive,         // true when trading.hard-gate-enabled=false (env flag)
        boolean skipDecision,              // true when Layer 1 would have returned SKIP/NO_TRADE
        String skipReason                  // e.g. "SKIP", "NO_TRADE", "VIX_EXTREME", "LOW_CONFIDENCE"
) {}
