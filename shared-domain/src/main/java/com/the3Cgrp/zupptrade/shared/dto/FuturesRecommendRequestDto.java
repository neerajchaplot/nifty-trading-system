package com.the3Cgrp.zupptrade.shared.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request to build a futures trade plan (Agent 2 §recommend).
 * agent1SignalId: optional/ignored — Agent 2 regenerates the signal from the mandatory admin
 *   commentary (futures_daily_commentary). Kept for backward compatibility with the UI payload.
 * runPhase: 900 = 09:00 primed plan, 931 = 09:31 confirm run (optional, defaults to 900).
 */
public record FuturesRecommendRequestDto(
        UUID agent1SignalId,
        @NotNull UUID userProfileId,
        Integer runPhase
) {}
