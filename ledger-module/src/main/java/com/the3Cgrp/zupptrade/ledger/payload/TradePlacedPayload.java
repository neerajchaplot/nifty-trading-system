package com.the3Cgrp.zupptrade.ledger.payload;

import java.util.List;

/**
 * Recorded when Agent 5 submits both legs to Upstox (POST /v2/order/multi/place succeeds).
 * Status remains CONFIRMED at this point — ACTIVE only after fills confirmed.
 */
public record TradePlacedPayload(
        List<LegOrder> legs
) {
    public record LegOrder(
            String correlationId,
            String orderId,
            String instrumentKey,
            String action,
            int quantity
    ) {}
}
