package com.the3Cgrp.zupptrade.shared.dto;

import com.the3Cgrp.zupptrade.shared.enums.ConfirmAction;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TradeConfirmRequestDto(
        @NotNull UUID tradeId,
        @NotNull ConfirmAction action,
        Integer overrideLots  // optional — user may reduce lots before confirming
) {}
