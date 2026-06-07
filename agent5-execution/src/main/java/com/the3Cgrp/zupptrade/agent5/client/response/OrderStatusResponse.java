package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Response from GET /v2/order/details?order_id={id} */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderStatusResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data")   OrderData data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderData(
            @JsonProperty("order_id")        String orderId,
            @JsonProperty("status")          String orderStatus,      // open | pending | complete | rejected | cancelled
            @JsonProperty("status_message")  String statusMessage,
            @JsonProperty("transaction_type") String transactionType,
            @JsonProperty("quantity")        int quantity,
            @JsonProperty("filled_quantity") int filledQuantity,
            @JsonProperty("pending_quantity") int pendingQuantity,
            @JsonProperty("average_price")   BigDecimal averagePrice  // VWAP fill price
    ) {}

    public boolean isComplete()  { return "success".equalsIgnoreCase(status) && data != null && "complete".equalsIgnoreCase(data.orderStatus()); }
    public boolean isOpen()      { return "success".equalsIgnoreCase(status) && data != null && ("open".equalsIgnoreCase(data.orderStatus()) || "pending".equalsIgnoreCase(data.orderStatus())); }
    public boolean isRejected()  { return "success".equalsIgnoreCase(status) && data != null && "rejected".equalsIgnoreCase(data.orderStatus()); }
    public boolean isCancelled() { return "success".equalsIgnoreCase(status) && data != null && "cancelled".equalsIgnoreCase(data.orderStatus()); }
}
