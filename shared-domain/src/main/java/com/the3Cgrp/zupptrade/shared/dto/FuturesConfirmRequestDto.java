package com.the3Cgrp.zupptrade.shared.dto;

import com.the3Cgrp.zupptrade.shared.enums.ConfirmAction;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * User's decision on a primed futures plan (Agent 2 §confirm).
 * On CONFIRM the chosen arm becomes the dormant (ARMED) plan; selectedArm is required.
 * overrideLots optionally replaces the engine-sized lots.
 */
public record FuturesConfirmRequestDto(
        @NotNull UUID planId,
        @NotNull ConfirmAction action,
        FutureArmType selectedArm,
        Integer overrideLots
) {}
