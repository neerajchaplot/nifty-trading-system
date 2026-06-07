package com.the3Cgrp.zupptrade.agent2.client.upstox.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// Verify field names against Upstox v2 API docs before going live
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxOptionChainResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data") List<UpstoxStrikeEntry> data
) {}
