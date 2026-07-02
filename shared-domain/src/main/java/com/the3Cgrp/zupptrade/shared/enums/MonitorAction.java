package com.the3Cgrp.zupptrade.shared.enums;

/**
 * Action returned by Agent 3 after each monitoring evaluation.
 * MonitorSchedulerService routes each action: READJUST → ReadjustmentService,
 * EXIT → triggerExit → Agent5ExitClient, PAUSE → AlertService.
 */
public enum MonitorAction {
    /** PoP ≥ 80%: trade is healthy, no action required. */
    HOLD,

    /** PoP 75–79%: approaching threshold, continue monitoring closely. */
    WATCH,

    /**
     * PoP 65–74% OR MTM loss ≥ T2 threshold: position needs adjustment.
     * ReadjustmentService handles: Agent5 exits current position, Agent1 re-scores,
     * Agent2 re-recommends with relaxed PoP gate, Agent5 re-enters new spread.
     */
    READJUST,

    /**
     * PoP < 65% OR MTM loss ≥ T3 threshold OR short strike breached.
     * Agent5ExitClient closes all legs immediately with market orders.
     */
    EXIT,

    /**
     * VIX > 24 (Extreme regime): auto-trading suspended.
     * No order actions taken. User must review manually.
     * Resume when VIX drops back below 24 on next evaluation cycle.
     */
    PAUSE
}
