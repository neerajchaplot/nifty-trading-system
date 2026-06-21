package com.the3Cgrp.zupptrade.ledger;

/**
 * All state-change events that can appear in the trade ledger.
 *
 * Only terminal or status-change events are recorded here.
 * Agent 3 intra-cycle monitoring evaluations have their own monitoring_evaluations table.
 */
public enum LedgerEventType {

    // ── Agent 2 events ────────────────────────────────────────────────────────
    TRADE_PENDING,          // Agent 2 generated a trade card, awaiting user confirmation
    TRADE_APPROVED,         // User confirmed — trade is CONFIRMED, queued for execution
    TRADE_REJECTED,         // User rejected or system rejected (gate failure)

    // ── Agent 5 — entry events ────────────────────────────────────────────────
    TRADE_PLACED,           // Both legs submitted to Upstox exchange
    TRADE_EXECUTED,         // Both legs filled — trade is ACTIVE
    TRADE_PARTIALLY_FILLED, // One leg filled, rollback in progress
    TRADE_FAILED,           // Entry execution failed (margin, exchange rejection, timeout)

    // ── Agent 3 / Agent 5 — exit events ──────────────────────────────────────
    TRADE_ADJUSTED,         // Position adjusted / rolled (READJUST threshold hit)
    TRADE_CLOSE_INITIATED,  // Agent 3 triggered exit — EXIT_IN_PROGRESS
    TRADE_CLOSED,           // Both exit legs filled — trade is CLOSED
    EXIT_FAILED,            // Exit attempt failed — status reverted to EXIT_FAILED

    // ── Lifecycle housekeeping ────────────────────────────────────────────────
    TRADE_EXPIRED,              // Reached expiry date without being closed
    TRADE_EXTERNALLY_CLOSED     // Detected closed via position reconciliation (user acted in Upstox app)
}
