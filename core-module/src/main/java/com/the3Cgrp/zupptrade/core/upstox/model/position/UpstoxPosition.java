package com.the3Cgrp.zupptrade.core.upstox.model.position;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row from GET /v2/portfolio/positions.
 *
 * quantity: net position quantity.
 *   Positive  → net long (more bought than sold today)
 *   Negative  → net short (more sold than bought today, typical for written options)
 *   Zero      → position is flat (opened and closed, or never held)
 *
 * instrument_token: Upstox uses ':' as separator (NSE_FO:NIFTY...) in position responses,
 *   whereas order placement and option chain use '|'. Normalise before comparing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxPosition(
        @JsonProperty("instrument_token") String instrumentToken,
        @JsonProperty("trading_symbol")   String tradingSymbol,
        @JsonProperty("quantity")         int quantity
) {}
