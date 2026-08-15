package com.the3Cgrp.zupptrade.core.upstox.model.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** Minimal projection of an Upstox /v2/instruments/search FUT record — only the fields we need. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxFuturesContract(
        @JsonProperty("instrument_key")    String instrumentKey,
        @JsonProperty("expiry")            LocalDate expiry,
        @JsonProperty("trading_symbol")    String tradingSymbol,
        @JsonProperty("underlying_symbol") String underlyingSymbol,
        @JsonProperty("instrument_type")   String instrumentType
) {}
