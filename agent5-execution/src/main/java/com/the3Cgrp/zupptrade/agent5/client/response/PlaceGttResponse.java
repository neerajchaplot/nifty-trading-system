package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response from POST /v3/order/gtt/place.
 *
 * <pre>{ "status":"success", "data": { "gtt_order_ids": ["GTT-…"] } }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceGttResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data")   Data data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(@JsonProperty("gtt_order_ids") List<String> gttOrderIds) {}

    public boolean isApiSuccess() { return "success".equalsIgnoreCase(status); }

    /** The GTT order id when the placement succeeded, else null. */
    public String gttOrderId() {
        List<String> ids = (data != null && data.gttOrderIds() != null) ? data.gttOrderIds() : List.of();
        return ids.isEmpty() ? null : ids.get(0);
    }
}
