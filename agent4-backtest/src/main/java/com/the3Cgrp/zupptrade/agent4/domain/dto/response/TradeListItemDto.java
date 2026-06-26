package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import com.the3Cgrp.zupptrade.agent4.domain.enums.TradeOutcome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record TradeListItemDto(

        UUID tradeId,
        String tradeCode,
        LocalDate expiryDate,
        String strategy,
        String spreadDirection,

        // Agent 1 signal summary
        String signalBias,
        String signalStrength,
        BigDecimal signalScore,
        String signalConfidence,

        // Lifecycle
        LocalDateTime entryDate,
        LocalDateTime exitDate,
        Integer holdingDays,

        // Position
        int lots,
        BigDecimal entryNetPremium,
        BigDecimal entrySpot,
        String entryVixRegime,

        // Result
        BigDecimal actualPnl,
        BigDecimal rocAchievedPct,
        int adjustmentCount,
        String closeReason,
        TradeOutcome outcome
) {}
