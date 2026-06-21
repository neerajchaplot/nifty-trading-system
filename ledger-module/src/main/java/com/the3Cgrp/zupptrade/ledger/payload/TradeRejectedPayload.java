package com.the3Cgrp.zupptrade.ledger.payload;

/**
 * Recorded when user rejects, system gate-fails, or expiry elapses on PENDING_CONFIRM.
 * Status transitions REJECTED.
 */
public record TradeRejectedPayload(
        String rejectedBy,   // e.g. "USER", "GATE1_POP", "GATE3_POPP_GAP", "SYSTEM_EXPIRY"
        String reason
) {}
