package com.the3Cgrp.zupptrade.shared.enums;

/**
 * Which specific threshold triggered the monitor action.
 * Stored in monitoring_evaluations for audit and debugging.
 */
public enum ThresholdHit {
    NONE,

    // Credit spread / short option thresholds
    T1_WATCH_NIFTY,
    T2_READJUST_NIFTY,
    T2_READJUST_PNL,
    T3_SHORT_STRIKE_BREACH,
    T3_EXIT_PNL,

    // Debit spread / long option thresholds
    DEBIT_T1_PROFIT_NIFTY,
    DEBIT_T2_PROFIT_NIFTY,
    DEBIT_T3_LOSS_PNL,
    DEBIT_THETA_EXIT,

    // VIX-based thresholds
    VIX_SPIKE_POP_RECALCULATED,
    VIX_EXTREME_PAUSE,

    // PoP thresholds (primary decision driver)
    POP_EXIT,
    POP_READJUST,
    POP_WATCH,

    // Expiry day (DTE=0) — proximity-based, replaces PoP ladder
    EXPIRY_DAY_PROXIMITY_EXIT,
    EXPIRY_DAY_PROXIMITY_WATCH
}
