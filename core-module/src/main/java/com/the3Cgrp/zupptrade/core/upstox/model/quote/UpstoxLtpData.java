package com.the3Cgrp.zupptrade.core.upstox.model.quote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Single instrument LTP entry from GET /v2/market-quote/ltp */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxLtpData(
        @JsonProperty("instrument_token") String instrumentToken,
        @JsonProperty("last_price")       BigDecimal lastPrice
) {}
