package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Response from POST /v2/charges/margin */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarginCheckResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data")   MarginData data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarginData(
            @JsonProperty("required_margin")  BigDecimal requiredMargin,   // before SPAN benefit
            @JsonProperty("final_margin")     BigDecimal finalMargin,      // after SPAN spread benefit
            @JsonProperty("available_margin") BigDecimal availableMargin   // free cash in account
    ) {}

    public boolean isApiSuccess() { return "success".equalsIgnoreCase(status) && data != null; }

    /** True if the account has enough free margin to place the entire spread. */
    public boolean hasSufficientMargin() {
        return isApiSuccess()
                && data.availableMargin() != null
                && data.finalMargin() != null
                && data.availableMargin().compareTo(data.finalMargin()) >= 0;
    }
}
