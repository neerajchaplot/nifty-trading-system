package com.the3Cgrp.zupptrade.core.upstox.model.chain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxStrikeData(
        @JsonProperty("instrument_key") String instrumentKey,
        @JsonProperty("market_data")    UpstoxMarketData marketData,
        @JsonProperty("option_greeks")  UpstoxOptionGreeks optionGreeks
) {}
