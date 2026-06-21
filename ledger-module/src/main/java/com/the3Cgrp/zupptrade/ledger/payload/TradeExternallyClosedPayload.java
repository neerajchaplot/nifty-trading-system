package com.the3Cgrp.zupptrade.ledger.payload;

/**
 * Recorded when Agent 3 position reconciliation detects that both legs
 * have net quantity = 0 in Upstox — the user closed the position manually.
 * Status transitions CLOSED.
 */
public record TradeExternallyClosedPayload(
        String shortInstrumentKey,
        String longInstrumentKey,
        String detectedBy   // AGENT3:SCHEDULER
) {}
