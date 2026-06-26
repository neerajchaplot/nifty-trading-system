package com.the3Cgrp.zupptrade.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Agent1SignalDto(
        UUID id,
        LocalDateTime timestamp,
        LocalDate expiryDate,
        Bias bias,
        Strength strength,
        BigDecimal compositeScore,
        BigDecimal confidenceScore,
        Confidence confidence,
        BigDecimal vixLevel,
        VixRegime vixRegime,
        String vixDirection,
        @JsonRawValue String scoreBreakdown,
        Boolean commentaryDivergence,
        @JsonRawValue String keyLevels,
        @JsonRawValue String dataGaps
) {}
