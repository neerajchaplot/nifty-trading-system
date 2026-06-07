package com.the3Cgrp.zupptrade.shared.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;

import java.math.BigDecimal;

/**
 * Represents one leg of an options spread — used across Agent 2, 3 and 5.
 *
 * instrumentKey: Upstox instrument key from the option chain response.
 *   Format: NFO_OPT|NIFTY|{YYYY-MM-DD}|{strike}|{CE|PE}
 *   Example: NFO_OPT|NIFTY|2026-06-10|24100|PE
 *   Required by Agent 5 for order placement. Populated by Agent 2's StrikeSelector.
 *   Null-safe annotated so existing serialised JSONB rows without this field
 *   still deserialise correctly (backwards compatible).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeLegDto(
        OptionType optionType,
        int strike,
        BigDecimal ltp,
        LegAction action,
        BigDecimal delta,
        BigDecimal pop,
        String instrumentKey   // Upstox instrument key — null for legacy records
) {}
