package com.the3Cgrp.zupptrade.core.upstox.model.chain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

// Boxed numeric fields (Long, not long): Upstox returns null market_data values for illiquid deep-OTM
// strikes. Jackson 3 rejects null → primitive (FAIL_ON_NULL_FOR_PRIMITIVES), which would fail the whole
// option-chain parse for Agent 1 scoring / Agent 3 monitoring. Consumers coalesce nulls to 0 (see oiOr0/prevOiOr0).
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxMarketData(
        BigDecimal ltp,
        @JsonProperty("close_price") BigDecimal closePrice,
        Long volume,
        Long oi,
        @JsonProperty("prev_oi")   Long prevOi,
        @JsonProperty("bid_price") BigDecimal bidPrice,
        @JsonProperty("ask_price") BigDecimal askPrice
) {
    /** Null-safe OI — illiquid strikes report null; treat as 0. */
    public long oiOr0()     { return oi     != null ? oi     : 0L; }
    /** Null-safe previous-session OI — illiquid strikes report null; treat as 0. */
    public long prevOiOr0() { return prevOi != null ? prevOi : 0L; }
}
