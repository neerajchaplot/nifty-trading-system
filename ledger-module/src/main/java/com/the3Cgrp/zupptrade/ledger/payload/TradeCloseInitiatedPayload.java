package com.the3Cgrp.zupptrade.ledger.payload;

import java.math.BigDecimal;

/**
 * Recorded when Agent 3 triggers EXIT — status transitions EXIT_IN_PROGRESS.
 */
public record TradeCloseInitiatedPayload(
        String triggerReason,        // T3_EXIT_BREACH, VIX_SPIKE, MTM_LOSS_LIMIT etc.
        BigDecimal markToMarketPnl,
        String initiatedBy           // AGENT3:SCHEDULER or AGENT5:SYSTEM
) {}
