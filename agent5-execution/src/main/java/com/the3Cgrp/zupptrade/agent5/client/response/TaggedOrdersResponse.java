package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response from GET /v2/order/details?tag={tag} — all orders placed under one trade tag.
 *
 * Used by the ambiguous-placement-failure reconciler: after a 5xx/timeout on placement we cannot
 * know what reached the exchange, so we ask the exchange for our footprint by tag and drive it flat.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaggedOrdersResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data")   List<TaggedOrder> data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaggedOrder(
            @JsonProperty("order_id")         String orderId,
            @JsonProperty("correlation_id")   String correlationId,
            @JsonProperty("status")           String orderStatus,       // open | pending | complete | rejected | cancelled
            @JsonProperty("transaction_type") String transactionType,   // BUY | SELL
            @JsonProperty("instrument_token") String instrumentToken,
            @JsonProperty("quantity")         int quantity,
            @JsonProperty("filled_quantity")  int filledQuantity,
            @JsonProperty("pending_quantity") int pendingQuantity,
            @JsonProperty("average_price")    BigDecimal averagePrice,
            @JsonProperty("tag")              String tag
    ) {
        public boolean isOpen() {
            return "open".equalsIgnoreCase(orderStatus) || "pending".equalsIgnoreCase(orderStatus);
        }
    }

    public boolean isApiSuccess() { return "success".equalsIgnoreCase(status); }

    /** Never-null accessor for the order list. */
    public List<TaggedOrder> orders() { return data != null ? data : List.of(); }
}
