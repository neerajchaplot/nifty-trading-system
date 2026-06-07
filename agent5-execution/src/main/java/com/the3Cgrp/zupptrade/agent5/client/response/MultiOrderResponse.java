package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response from POST /v2/order/multi/place.
 *
 * data[]   — successfully placed orders; each entry has correlation_id + order_id
 * errors[] — orders that failed; each entry has correlation_id + error details
 * summary  — counts: total, success, error, payload_error
 *
 * Key rule from Upstox:
 *   If payload_error > 0 → ZERO orders were submitted to the exchange.
 *   This is the all-or-nothing guarantee at the validation level.
 *   exchange-level rejections (risk, liquidity) still cause partial execution.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiOrderResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data")   List<OrderData> data,
        @JsonProperty("errors") List<OrderError> errors,
        @JsonProperty("summary") Summary summary
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderData(
            @JsonProperty("correlation_id") String correlationId,
            @JsonProperty("order_id")       String orderId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderError(
            @JsonProperty("correlation_id") String correlationId,
            @JsonProperty("error_code")     String errorCode,
            @JsonProperty("message")        String message
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(
            @JsonProperty("total")         int total,
            @JsonProperty("success")       int success,
            @JsonProperty("error")         int error,
            @JsonProperty("payload_error") int payloadError
    ) {}

    public boolean isApiSuccess() { return "success".equalsIgnoreCase(status); }

    /** True if Upstox rejected the entire batch due to payload/format errors — nothing was sent. */
    public boolean hasPayloadErrors() {
        return summary != null && summary.payloadError() > 0;
    }

    /** True if all legs were accepted and routed to the exchange. */
    public boolean allLegsAccepted() {
        return isApiSuccess() && summary != null
                && summary.error() == 0 && summary.payloadError() == 0
                && summary.success() == summary.total();
    }
}
