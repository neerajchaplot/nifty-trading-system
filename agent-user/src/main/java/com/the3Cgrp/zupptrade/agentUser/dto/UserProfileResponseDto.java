package com.the3Cgrp.zupptrade.agentUser.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record UserProfileResponseDto(
        UUID id,
        String userId,
        BigDecimal capital,
        BigDecimal minPop,
        BigDecimal maxLossPct,
        BigDecimal maxPopPoppGap,
        BigDecimal minRocPct,
        int spreadWidthMin,
        int spreadWidthMax,
        BigDecimal tier1aWeight,
        BigDecimal tier1bWeight,
        BigDecimal tier2Weight,
        BigDecimal tier3Weight,
        BigDecimal tier4Weight
) {}
