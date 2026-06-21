package com.the3Cgrp.zupptrade.ledger.payload;

/**
 * Recorded when an exit attempt fails — status reverts to EXIT_FAILED.
 * Agent 3 retries on the next monitoring cycle.
 */
public record ExitFailedPayload(
        String failureStage,   // STATUS_UPDATE, ORDER_PLACEMENT, MISSING_INSTRUMENT_KEY
        String reason,
        int retryAttempt       // incremented each time EXIT_FAILED is recorded
) {}
