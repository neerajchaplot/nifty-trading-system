package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response from POST /v3/order/place.
 *
 * <pre>{ "status":"success", "data": { "order_ids": ["1644490272000"] } }</pre>
 *
 * {@code data.order_ids} is an ARRAY because V3 supports slicing. We never slice, so a successful
 * placement returns exactly ONE order_id; the caller treats any other count as an error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceOrderV3Response(
        @JsonProperty("status") String status,
        @JsonProperty("data")   Data data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(@JsonProperty("order_ids") List<String> orderIds) {}

    public boolean isApiSuccess() { return "success".equalsIgnoreCase(status); }

    /** Never-null accessor for the returned order ids. */
    public List<String> orderIds() {
        return (data != null && data.orderIds() != null) ? data.orderIds() : List.of();
    }

    /** The single order_id when exactly one was returned (no slicing); null otherwise. */
    public String singleOrderId() {
        List<String> ids = orderIds();
        return ids.size() == 1 ? ids.get(0) : null;
    }
}
