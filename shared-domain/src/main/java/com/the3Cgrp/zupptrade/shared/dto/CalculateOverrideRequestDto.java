package com.the3Cgrp.zupptrade.shared.dto;

import com.the3Cgrp.zupptrade.shared.enums.OptionType;
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
        @NotNull Integer peShortStrike,   // primary pair short strike (CE or PE per optionType)
        @NotNull Integer peLongStrike,    // primary pair long strike
        Integer ceShortStrike,         // second (CE) pair — Iron Condor only; null for 2-leg spreads
        Integer ceLongStrike,
        @NotNull @Min(1) Integer lots,
        // Option type of the PRIMARY pair (peShort/peLong). Null → PE (back-compat).
        // For a bear/bull CALL spread this is CE; for put spreads and Iron Condor's primary pair, PE.
        OptionType optionType
) {}
