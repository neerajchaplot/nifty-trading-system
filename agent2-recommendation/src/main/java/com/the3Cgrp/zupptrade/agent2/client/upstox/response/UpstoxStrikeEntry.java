package com.the3Cgrp.zupptrade.agent2.client.upstox.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxStrikeEntry(
        @JsonProperty("expiry") String expiry,
        @JsonProperty("strike_price") double strikePrice,
        @JsonProperty("underlying_spot_price") Double underlyingSpotPrice,   // nullable — absent on some entries
        @JsonProperty("pcr") Double pcr,                                     // nullable — undefined when one side has zero OI (deep OTM)
        @JsonProperty("call_options") UpstoxLegData callOptions,
        @JsonProperty("put_options") UpstoxLegData putOptions
) {}
