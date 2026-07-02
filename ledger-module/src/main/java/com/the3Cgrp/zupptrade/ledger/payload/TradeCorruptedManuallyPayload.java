package com.the3Cgrp.zupptrade.ledger.payload;

import java.util.List;

/**
 * Recorded when Agent 3 position reconciliation detects a partial close:
 * one or more legs have net quantity = 0 in Upstox while other legs remain open.
 * Status transitions to CORRUPTED_MANUALLY. Monitoring stops immediately.
 * Manual intervention required to close remaining open legs on Upstox.
 */
public record TradeCorruptedManuallyPayload(
        List<String> flatLegs,       // instrument keys with qty = 0
        List<String> openLegs,       // instrument keys still holding position
        String detectedBy            // AGENT3:SCHEDULER
) {}
