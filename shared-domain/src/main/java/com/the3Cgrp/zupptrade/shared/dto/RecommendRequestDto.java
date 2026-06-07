package com.the3Cgrp.zupptrade.shared.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RecommendRequestDto(
        @NotNull UUID userProfileId,
        @NotNull UUID agent1SignalId
) {}
