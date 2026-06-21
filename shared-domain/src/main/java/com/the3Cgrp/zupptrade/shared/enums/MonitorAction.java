package com.the3Cgrp.zupptrade.shared.enums;

/**
 * Action returned by Agent 3 after each monitoring evaluation.
 * Orchestrator acts on READJUST and EXIT; PAUSE suspends auto-trading.
 */
public enum MonitorAction {
    /** PoP ≥ 80%: trade is healthy, no action required. */
    HOLD,

    /** PoP 75–79%: approaching threshold, continue monitoring closely. */
    WATCH,

    /**
     * PoP 65–74% OR MTM loss ≥ T2 threshold: position needs adjustment.
     * Orchestrator coordinates: Agent 5 closes current position, Agent 2 re-recommends,
     * Agent 5 re-enters new spread.
     * TODO(orchestrator): wire READJUST → Agent5.exit → Agent2.recommend → Agent5.execute
     */
    READJUST,

    /**
     * PoP < 65% OR MTM loss ≥ T3 threshold OR short strike breached.
     * Orchestrator must call Agent 5 to close all legs immediately.
     * TODO(orchestrator): wire EXIT → Agent5.exitAll with market order
     */
    EXIT,

    /**
     * VIX > 24 (Extreme regime): auto-trading suspended.
     * No order actions taken. User must review manually.
     * Resume when VIX drops back below 24 on next evaluation cycle.
     * TODO(agent3): task #17 — define full PAUSE/RESUME lifecycle
     */
    PAUSE
}
