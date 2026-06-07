package com.the3Cgrp.zupptrade.agent5.client.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for POST /v2/charges/margin.
 *
 * All legs submitted together — Upstox computes the net SPAN margin
 * with spread benefit applied, giving a more accurate (lower) figure
 * than checking each leg individually.
 */
public record MarginCheckRequest(
        @JsonProperty("instruments") List<Instrument> instruments
) {
    public record Instrument(
            @JsonProperty("instrument_key")   String instrumentKey,
            @JsonProperty("transaction_type") String transactionType,  // BUY or SELL
            @JsonProperty("quantity")         int quantity,
            @JsonProperty("product")          String product,           // D = NRML
            @JsonProperty("price")            BigDecimal price          // LTP at recommendation time
    ) {}
}
