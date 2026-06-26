package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SignalChapter(
        UUID signalId,
        LocalDateTime scoredAt,
        String bias,
        String strength,
        BigDecimal compositeScore,
        BigDecimal confidenceScore,
        String confidenceLabel,
        BigDecimal vixLevel,
        String vixRegime,
        String vixDirection,
        boolean commentaryDivergence,
        /** Tier name → weighted score contribution, e.g. {"Tier 1A – Price": 0.12, ...} */
        Map<String, BigDecimal> tierScores,
        List<String> dataGaps
) {}
