package com.the3Cgrp.zupptrade.shared.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request to calculate metrics for a manually overridden trade.
 * The backend fetches live LTP from Upstox for each provided strike, runs Black-Scholes PoP,
 * and returns computed metrics without persisting anything.
 */
public record CalculateOverrideRequestDto(
        @NotNull UUID tradeId,         // used to resolve expiry, DTE, lot size, and capital
        @NotNull Integer peShortStrike,
        @NotNull Integer peLongStrike,
        Integer ceShortStrike,         // null for 2-leg spreads
        Integer ceLongStrike,
        @NotNull @Min(1) Integer lots
) {}
