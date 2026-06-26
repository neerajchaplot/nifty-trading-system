package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Response from GET /v2/user/fund-and-margin?segment=SEC */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FundsAndMarginResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data")   FundsData data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FundsData(
            @JsonProperty("available_margin") BigDecimal availableMargin
    ) {}

    public boolean isApiSuccess() { return "success".equalsIgnoreCase(status) && data != null; }

    /** Returns available free margin, or ZERO if the response was unavailable. */
    public BigDecimal availableMargin() {
        return (isApiSuccess() && data.availableMargin() != null)
                ? data.availableMargin() : BigDecimal.ZERO;
    }
}
