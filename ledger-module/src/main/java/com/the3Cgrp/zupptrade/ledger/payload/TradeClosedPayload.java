package com.the3Cgrp.zupptrade.ledger.payload;

import java.math.BigDecimal;
import java.util.List;

/**
 * Recorded when both exit legs fill — status transitions CLOSED.
 */
public record TradeClosedPayload(
        String closeReason,
        List<ExitFill> exitFills,
        BigDecimal actualPnl,
        BigDecimal netExitPremiumPerUnit
) {
    public record ExitFill(
            String orderId,
            String instrumentKey,
            String action,
            int quantity,
            BigDecimal avgPrice
    ) {}
}
