package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import com.the3Cgrp.zupptrade.agent4.domain.enums.TradeOutcome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TradeAuditResponse(
        UUID tradeId,
        String tradeCode,
        String status,
        LocalDate expiryDate,
        TradeOutcome outcome,
        BigDecimal actualPnl,
        String closeReason,

        SignalChapter signal,
        RecommendationChapter recommendation,
        ExecutionChapter execution,
        MonitoringChapter monitoring
) {}
