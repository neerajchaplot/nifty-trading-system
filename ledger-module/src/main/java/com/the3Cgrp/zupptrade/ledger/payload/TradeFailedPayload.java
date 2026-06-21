package com.the3Cgrp.zupptrade.ledger.payload;

/**
 * Recorded when entry execution fails for any reason (margin insufficient,
 * exchange rejection, fill timeout, rollback after partial fill).
 * Status transitions REJECTED.
 */
public record TradeFailedPayload(
        String failureStage,  // MARGIN_CHECK, ORDER_PLACEMENT, FILL_TIMEOUT, ROLLBACK
        String reason,
        String upstoxErrorCode   // null if failure was not an Upstox API error
) {}
