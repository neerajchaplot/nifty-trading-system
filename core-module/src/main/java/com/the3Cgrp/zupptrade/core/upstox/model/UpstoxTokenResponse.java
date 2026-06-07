package com.the3Cgrp.zupptrade.core.upstox.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in")   long expiresIn,
        @JsonProperty("token_type")   String tokenType
) {}
