package com.the3Cgrp.zupptrade.agent3.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.the3Cgrp.zupptrade.shared.enums.MonitorAction;
import com.the3Cgrp.zupptrade.shared.enums.ThresholdHit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationResponse(
        UUID evaluationId,
        UUID tradeId,
        MonitorAction action,
        ThresholdHit thresholdHit,
        String reason,
        BigDecimal spotPrice,
        BigDecimal vixLevel,
        BigDecimal currentPop,
        BigDecimal markToMarketPnl,
        BigDecimal currentNetPremium,
        BigDecimal shortLegLtp,
        BigDecimal longLegLtp,
        Instant evaluatedAt
) {}
