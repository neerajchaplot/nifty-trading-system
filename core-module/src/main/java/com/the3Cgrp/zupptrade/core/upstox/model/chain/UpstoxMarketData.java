package com.the3Cgrp.zupptrade.core.upstox.model.chain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxMarketData(
        BigDecimal ltp,
        @JsonProperty("close_price") BigDecimal closePrice,
        long volume,
        long oi,
        @JsonProperty("prev_oi")   long prevOi,
        @JsonProperty("bid_price") BigDecimal bidPrice,
        @JsonProperty("ask_price") BigDecimal askPrice
) {}
