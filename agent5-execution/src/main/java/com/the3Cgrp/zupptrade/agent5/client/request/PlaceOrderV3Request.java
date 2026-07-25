package com.the3Cgrp.zupptrade.agent5.client.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Request body for POST /v3/order/place (single order).
 *
 * V3 replaces V2 multi/place. We place ONE leg per call (sequential execution), and each leg is
 * identified by a UNIQUE per-leg {@code tag} (max 40 chars) — the tag replaces V2's correlation_id
 * (V3 has no correlation_id field).
 *
 * {@code slice} is always false: we do NOT auto-slice. A leg whose quantity exceeds the exchange
 * freeze limit is rejected before placement (see Agent5ExecutionProperties.maxOrderQuantity).
 * {@code trigger_price} is required by V3 even for LIMIT/MARKET (send 0).
 */
public record PlaceOrderV3Request(
        @JsonProperty("instrument_token")   String instrumentToken,
        @JsonProperty("transaction_type")   String transactionType,   // BUY | SELL
        @JsonProperty("order_type")         String orderType,         // LIMIT | MARKET
        @JsonProperty("product")            String product,           // D = NRML
        @JsonProperty("validity")           String validity,          // DAY
        @JsonProperty("quantity")           int quantity,
        @JsonProperty("price")              BigDecimal price,         // 0 for MARKET
        @JsonProperty("trigger_price")      BigDecimal triggerPrice,  // required by V3 — 0 for LIMIT/MARKET
        @JsonProperty("disclosed_quantity") int disclosedQuantity,
        @JsonProperty("is_amo")             boolean isAmo,
        @JsonProperty("slice")              boolean slice,            // always false — no auto-slicing
        @JsonProperty("tag")                String tag                // unique per leg — replaces correlation_id
) {
    /** LIMIT entry order for a spread leg. */
    public static PlaceOrderV3Request limit(String instrumentKey, String transactionType, String product,
                                            int quantity, BigDecimal price, String tag) {
        return new PlaceOrderV3Request(instrumentKey, transactionType, "LIMIT", product, "DAY",
                quantity, price, BigDecimal.ZERO, 0, false, false, tag);
    }

    /** MARKET order for exit / rollback. */
    public static PlaceOrderV3Request market(String instrumentKey, String transactionType, String product,
                                             int quantity, String tag) {
        return new PlaceOrderV3Request(instrumentKey, transactionType, "MARKET", product, "DAY",
                quantity, BigDecimal.ZERO, BigDecimal.ZERO, 0, false, false, tag);
    }
}
