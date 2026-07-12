package com.the3Cgrp.zupptrade.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Agent1SignalDto(
        UUID id,
        // Zone-aware instant (serialised with UTC offset, e.g. "…Z") so any client
        // computes signal age correctly regardless of its own timezone. A zoneless
        // LocalDateTime here was misread by browsers when the server JVM ran in UTC.
        OffsetDateTime timestamp,
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
        @JsonRawValue String dataGaps,
        // Nifty 50 spot captured at scoring time (last close when market is shut).
        BigDecimal spot
) {}
