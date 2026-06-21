package com.the3Cgrp.zupptrade.shared.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record RecommendRequestDto(
        @NotNull UUID userProfileId,
        @NotNull UUID agent1SignalId,
        BigDecimal relaxedGate1PopPct  // null = standard gate; non-null overrides G1 threshold (readjustment)
) {
    public static RecommendRequestDto standard(UUID userProfileId, UUID agent1SignalId) {
        return new RecommendRequestDto(userProfileId, agent1SignalId, null);
    }
}
