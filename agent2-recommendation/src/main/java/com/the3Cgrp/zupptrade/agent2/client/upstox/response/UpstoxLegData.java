package com.the3Cgrp.zupptrade.agent2.client.upstox.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// Verify field names against Upstox v2 API docs before going live
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxLegData(
        @JsonProperty("instrument_key") String instrumentKey,
        @JsonProperty("market_data") UpstoxMarketData marketData,
        @JsonProperty("option_greeks") UpstoxOptionGreeks optionGreeks
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstoxMarketData(
            @JsonProperty("ltp") double ltp,
            @JsonProperty("oi") double oi,
            @JsonProperty("prev_oi") double prevOi,
            @JsonProperty("bid_price") double bidPrice,
            @JsonProperty("ask_price") double askPrice
            // pcr is NOT here — it lives at data[].pcr (top-level per strike in UpstoxStrikeEntry)
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstoxOptionGreeks(
            @JsonProperty("iv") double iv,
            @JsonProperty("delta") double delta,
            @JsonProperty("theta") double theta,
            @JsonProperty("gamma") double gamma,
            @JsonProperty("vega") double vega,
            @JsonProperty("pop") double pop
    ) {}
}
