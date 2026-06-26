package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Response from POST /v2/charges/margin.
 * This endpoint returns only the margin REQUIRED for a set of orders.
 * Available account funds must be fetched separately via GET /v2/user/fund-and-margin
 * (see FundsAndMarginResponse).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarginCheckResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data")   MarginData data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarginData(
            @JsonProperty("required_margin") BigDecimal requiredMargin,  // before SPAN spread benefit
            @JsonProperty("final_margin")    BigDecimal finalMargin      // after SPAN spread benefit — use this
    ) {}

    public boolean isApiSuccess() { return "success".equalsIgnoreCase(status) && data != null; }
}
