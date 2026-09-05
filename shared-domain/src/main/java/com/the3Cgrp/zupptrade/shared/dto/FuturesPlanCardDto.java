package com.the3Cgrp.zupptrade.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import com.the3Cgrp.zupptrade.shared.enums.OpenZone;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The full futures trade card — all four arms plus every calculation, for transparency.
 * The end user selects which ALLOWED/RECOMMENDED arm to arm (Agent 2 output).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FuturesPlanCardDto(
        UUID planId,
        String planCode,
        FuturePlanStatus status,
        LocalDate tradeDate,
        int runPhase,
        String instrumentKey,
        // Agent 1 direction snapshot
        Bias bias,
        BigDecimal confidenceScore,
        Confidence confidenceLabel,
        OpenZone openZone,
        // Levels + context
        FuturesCamarillaDto keyLevels,
        FuturesPriorOhlcDto priorOhlc,
        // The session-open reference used to classify the day's zone: the GIFT-implied open when the
        // plan is primed pre-market, else today's actual opening print. Fixed for the day (not live spot).
        BigDecimal openPx,
        // Live Nifty level the arms' reachability was judged against on this read (nullable if the live
        // level was unavailable). Distinct from openPx — this one moves; openPx is the fixed session open.
        BigDecimal currentLevel,
        // Gates (display)
        boolean confidenceGatePassed,
        BigDecimal minConfidence,
        BigDecimal compressionRci,
        BigDecimal compressionThreshold,
        boolean compressed,
        BigDecimal roundTripCostPoints,
        // Decision
        FutureArmType primaryArm,
        String noTradeReason,
        List<FuturesArmCardDto> arms,
        OffsetDateTime createdAt
) {}
