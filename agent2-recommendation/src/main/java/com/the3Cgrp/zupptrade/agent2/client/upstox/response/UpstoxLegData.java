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
    // Boxed (Double, not double): Upstox returns null market_data/greeks fields pre-open and for illiquid
    // deep-OTM strikes. Jackson 3 rejects null → primitive (FAIL_ON_NULL_FOR_PRIMITIVES), which was failing
    // the whole option-chain parse. `close_price` (previous session close) is Upstox's fallback when the
    // session has no last-traded-price yet — the mapper prefers ltp and falls back to closePrice.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstoxMarketData(
            @JsonProperty("ltp") Double ltp,
            @JsonProperty("close_price") Double closePrice,
            @JsonProperty("oi") Double oi,
            @JsonProperty("prev_oi") Double prevOi,
            @JsonProperty("bid_price") Double bidPrice,
            @JsonProperty("ask_price") Double askPrice
            // pcr is NOT here — it lives at data[].pcr (top-level per strike in UpstoxStrikeEntry)
    ) {
        /** Effective price: last-traded price when the session is live, else previous close (pre-open). */
        public Double effectivePrice() {
            return (ltp != null && ltp > 0) ? ltp : closePrice;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstoxOptionGreeks(
            @JsonProperty("iv") Double iv,
            @JsonProperty("delta") Double delta,
            @JsonProperty("theta") Double theta,
            @JsonProperty("gamma") Double gamma,
            @JsonProperty("vega") Double vega,
            @JsonProperty("pop") Double pop
    ) {}
}
