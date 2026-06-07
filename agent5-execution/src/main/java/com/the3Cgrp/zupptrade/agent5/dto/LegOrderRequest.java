package com.the3Cgrp.zupptrade.agent5.dto;

import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One leg of the spread to execute. Comes from the orchestrator/UI after Agent 2 confirms.
 *
 * instrumentKey: taken directly from TradeLegDto.instrumentKey(), which Agent 2's
 *   StrikeSelector populated from the Upstox option chain. Do not construct this value —
 *   use exactly what Upstox returned so the order key matches their system.
 *
 * quantity: lots × lotSize — total units, not lots.
 * limitPrice: Agent 2's LTP at recommendation time, used as the initial LIMIT order price.
 */
public record LegOrderRequest(
        @NotBlank
        String instrumentKey,

        @NotNull
        OptionType optionType,

        int strike,

        @NotNull
        LegAction action,

        @NotNull
        @DecimalMin(value = "0.05", message = "Limit price must be positive")
        BigDecimal limitPrice,

        @Min(value = 1, message = "Quantity must be at least 1 (lots × lotSize)")
        int quantity
) {}
