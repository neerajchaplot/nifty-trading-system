package com.the3Cgrp.zupptrade.agent5.client.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for POST /v3/order/gtt/place — a multi-leg GTT (spec §4).
 *
 * {@code type: MULTIPLE} = one OCO order carrying ENTRY + TARGET + STOPLOSS rules: when the entry
 * fills, the target and stop become active and whichever triggers first cancels the other. Futures
 * use index points directly (no delta translation) and product "I" (intraday). All three rules are
 * IMMEDIATE — Agent 3's state machine already confirmed the break, so the GTT executes now.
 */
public record PlaceGttRequest(
        @JsonProperty("type")             String type,              // MULTIPLE
        @JsonProperty("quantity")         int quantity,            // lots × lot size
        @JsonProperty("product")          String product,          // I = intraday
        @JsonProperty("instrument_token") String instrumentToken,  // NSE_FO|<nifty_fut_token>
        @JsonProperty("transaction_type") String transactionType,  // BUY (long) | SELL (short)
        @JsonProperty("rules")            List<Rule> rules,
        @JsonProperty("tag")              String tag               // ZUPP_{planId8}
) {
    public record Rule(
            @JsonProperty("strategy")      String strategy,       // ENTRY | TARGET | STOPLOSS
            @JsonProperty("trigger_type")  String triggerType,    // IMMEDIATE
            @JsonProperty("trigger_price") BigDecimal triggerPrice
    ) {}

    /**
     * Build the ENTRY+TARGET+STOPLOSS OCO for one arm.
     * Long: BUY, target above / stop below. Short: SELL, target below / stop above (spec §4).
     */
    public static PlaceGttRequest oco(String instrumentToken, String transactionType, int quantity,
                                      BigDecimal entry, BigDecimal target, BigDecimal stop, String tag) {
        return new PlaceGttRequest("MULTIPLE", quantity, "I", instrumentToken, transactionType,
                List.of(
                        new Rule("ENTRY",    "IMMEDIATE", entry),
                        new Rule("TARGET",   "IMMEDIATE", target),
                        new Rule("STOPLOSS", "IMMEDIATE", stop)),
                tag);
    }
}
