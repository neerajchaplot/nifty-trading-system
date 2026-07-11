package com.the3Cgrp.zupptrade.agentUser.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record UpdateUserProfileRequestDto(
        @NotNull @DecimalMin("10000") BigDecimal capital,
        @NotNull @DecimalMin("0.50") @DecimalMax("0.99") BigDecimal minPop,
        @NotNull @DecimalMin("0.10") @DecimalMax("10.00") BigDecimal maxLossPct,
        @NotNull @DecimalMin("1.00") @DecimalMax("50.00") BigDecimal maxPopPoppGap,
        @NotNull @DecimalMin("0.10") @DecimalMax("10.00") BigDecimal minRocPct,
        @Min(50) @Max(500) int spreadWidthMin,
        @Min(50) @Max(500) int spreadWidthMax,
        @NotNull @DecimalMin("0.01") @DecimalMax("0.99") BigDecimal tier1aWeight,
        @NotNull @DecimalMin("0.01") @DecimalMax("0.99") BigDecimal tier1bWeight,
        @NotNull @DecimalMin("0.01") @DecimalMax("0.99") BigDecimal tier2Weight,
        @NotNull @DecimalMin("0.01") @DecimalMax("0.99") BigDecimal tier3Weight,
        @NotNull @DecimalMin("0.01") @DecimalMax("0.99") BigDecimal tier4Weight
) {}
