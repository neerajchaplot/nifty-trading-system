package com.the3Cgrp.zupptrade.agent5.service;

import java.util.UUID;

/**
 * Builds Upstox order tag and correlation_id values from a trade UUID.
 *
 * tag          — shared across all legs of one trade; queryable from Upstox:
 *                GET /v2/order/details?tag=ZUPP_A1B2C3D4
 *                Format: ZUPP_{first 8 chars of tradeId uppercase}
 *                Max 20 chars per Upstox limit; ZUPP_ prefix = 5 + 8 = 13 chars ✓
 *
 * correlationId — unique per leg within the multi-order batch.
 *                Format: {tag}_L{legIndex}   e.g. ZUPP_A1B2C3D4_L0, ZUPP_A1B2C3D4_L1
 *                Returned in multi/place response so we map order_id → leg without extra state.
 *
 * exitTag      — same as tag with _X suffix; identifies exit/rollback orders.
 */
public final class OrderTagBuilder {

    private OrderTagBuilder() {}

    public static String tag(UUID tradeId) {
        return "ZUPP_" + tradeId.toString().substring(0, 8).toUpperCase();
    }

    public static String correlationId(UUID tradeId, int legIndex) {
        return tag(tradeId) + "_L" + legIndex;
    }

    public static String exitTag(UUID tradeId) {
        return tag(tradeId) + "_X";
    }

    public static String exitCorrelationId(UUID tradeId, int legIndex) {
        return exitTag(tradeId) + "_L" + legIndex;
    }
}
