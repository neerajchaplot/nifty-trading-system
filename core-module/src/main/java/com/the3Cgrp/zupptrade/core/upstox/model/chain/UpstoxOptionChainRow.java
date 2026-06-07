package com.the3Cgrp.zupptrade.core.upstox.model.chain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** One row in the Upstox option chain response — one strike price with CE and PE data. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxOptionChainRow(
        String expiry,
        @JsonProperty("strike_price")          BigDecimal strikePrice,
        @JsonProperty("underlying_spot_price") BigDecimal underlyingSpotPrice,
        @JsonProperty("call_options")          UpstoxStrikeData callOptions,
        @JsonProperty("put_options")           UpstoxStrikeData putOptions
) {}
