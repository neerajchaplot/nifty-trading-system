package com.the3Cgrp.zupptrade.ledger.payload;

import java.math.BigDecimal;
import java.util.List;

/**
 * Recorded when both legs fill — status transitions ACTIVE.
 */
public record TradeExecutedPayload(
        List<LegFill> fills,
        BigDecimal actualNetPremiumPerUnit,
        boolean slippageAlert,
        String slippageMessage   // null when slippageAlert == false
) {
    public record LegFill(
            String orderId,
            String instrumentKey,
            String action,
            int quantity,
            BigDecimal avgPrice
    ) {}
}
