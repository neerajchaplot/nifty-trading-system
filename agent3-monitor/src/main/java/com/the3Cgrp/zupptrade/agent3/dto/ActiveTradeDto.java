package com.the3Cgrp.zupptrade.agent3.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.enums.MonitorAction;
import com.the3Cgrp.zupptrade.shared.enums.ThresholdHit;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * UI-facing DTO that combines monitor config (static at entry) with the
 * latest evaluation snapshot (live P&L, spot, threshold state).
 * Returned by GET /api/v1/agent3/active-trades.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActiveTradeDto(
        UUID tradeId,
        String tradeCode,
        TradeStatus status,
        LocalDate expiryDate,
        MonitorConfigDto monitorConfig,
        // Latest evaluation snapshot — null if no evaluation run yet
        MonitorAction lastAction,
        ThresholdHit lastThresholdHit,
        BigDecimal spotPrice,
        BigDecimal vixLevel,
        BigDecimal currentPop,
        BigDecimal markToMarketPnl,
        BigDecimal shortLegLtp,
        BigDecimal longLegLtp,
        Instant lastEvaluatedAt
) {}
