package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Response from GET /v2/user/get-funds-and-margin?segment=SEC.
 *
 * Upstox nests margin values under a segment key ("equity" / "commodity"), NOT flat under data.
 * Since July 2025 the "equity" object carries the combined equity+commodity account funds, so
 * we read the equity segment for the whole-account margin figure.
 *
 * <pre>
 * { "status": "success",
 *   "data": { "equity":   { "used_margin": 0.8, "available_margin": 15507.46, ... },
 *             "commodity": { ... } } }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FundsAndMarginResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data")   FundsData data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FundsData(
            @JsonProperty("equity")    SegmentFunds equity,
            @JsonProperty("commodity") SegmentFunds commodity
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SegmentFunds(
            @JsonProperty("available_margin") BigDecimal availableMargin,
            @JsonProperty("used_margin")      BigDecimal usedMargin
    ) {}

    public boolean isApiSuccess() {
        return "success".equalsIgnoreCase(status) && data != null && data.equity() != null;
    }

    /** Returns available free margin (equity segment), or ZERO if the response was unavailable. */
    public BigDecimal availableMargin() {
        return (isApiSuccess() && data.equity().availableMargin() != null)
                ? data.equity().availableMargin() : BigDecimal.ZERO;
    }

    /** Returns margin currently blocked by open positions (equity segment), or ZERO if unavailable. */
    public BigDecimal usedMargin() {
        return (isApiSuccess() && data.equity().usedMargin() != null)
                ? data.equity().usedMargin() : BigDecimal.ZERO;
    }
}
