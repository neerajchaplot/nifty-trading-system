package com.the3Cgrp.zupptrade.agent5.client.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for POST /v2/order/multi/place.
 *
 * Upstox validates the entire payload before routing any order to the exchange.
 * If any leg has a payload_error, ZERO orders are submitted — the closest to
 * all-or-nothing that the Upstox API provides.
 *
 * correlation_id: set by us per leg, returned in both data[] and errors[] of the
 *   response so we can map each order_id back to our specific leg without state.
 *   Format: ZUPP_{tradeId8}_L{index}  e.g. ZUPP_A1B2C3D4_L0, ZUPP_A1B2C3D4_L1
 *
 * tag: shared across all legs of the same trade.
 *   Format: ZUPP_{tradeId8}  e.g. ZUPP_A1B2C3D4
 *   Queryable from Upstox: GET /v2/order/details?tag=ZUPP_A1B2C3D4
 */
public record MultiOrderRequest(
        @JsonProperty("orders") List<OrderLeg> orders
) {
    public record OrderLeg(
            @JsonProperty("instrument_token") String instrumentToken,
            @JsonProperty("transaction_type") String transactionType,   // BUY or SELL
            @JsonProperty("order_type")        String orderType,         // LIMIT or MARKET
            @JsonProperty("product")           String product,           // D = NRML
            @JsonProperty("validity")          String validity,          // DAY
            @JsonProperty("quantity")          int quantity,
            @JsonProperty("price")             BigDecimal price,         // 0 for MARKET
            @JsonProperty("disclosed_quantity") int disclosedQuantity,
            @JsonProperty("trigger_price")     BigDecimal triggerPrice,
            @JsonProperty("is_amo")            boolean isAmo,
            @JsonProperty("slice")             boolean slice,
            @JsonProperty("tag")               String tag,
            @JsonProperty("correlation_id")    String correlationId
    ) {
        /** Factory: LIMIT entry order for a spread leg. */
        public static OrderLeg limit(String instrumentKey, String transactionType,
                                     String product, int quantity, BigDecimal price,
                                     String tag, String correlationId) {
            return new OrderLeg(instrumentKey, transactionType, "LIMIT", product,
                    "DAY", quantity, price, 0, BigDecimal.ZERO, false, false,
                    tag, correlationId);
        }

        /** Factory: MARKET order for exit or rollback. */
        public static OrderLeg market(String instrumentKey, String transactionType,
                                      String product, int quantity,
                                      String tag, String correlationId) {
            return new OrderLeg(instrumentKey, transactionType, "MARKET", product,
                    "DAY", quantity, BigDecimal.ZERO, 0, BigDecimal.ZERO, false, false,
                    tag, correlationId);
        }
    }
}
