package com.the3Cgrp.zupptrade.shared.enums;

/**
 * The four-arm grid of the futures engine (spec §2.3).
 * Each arm has a fixed direction and a Camarilla trigger level:
 *   LONG_ROTATION   — reclaim L3, long, target pivot→H3
 *   SHORT_ROTATION  — reject  H3, short, target pivot→L3
 *   LONG_BREAKOUT   — accept above H4, long, target round/H4+range
 *   SHORT_BREAKDOWN — lose L4, short, target round/L4-range
 */
public enum FutureArmType {
    LONG_ROTATION(TradeDirection.LONG),
    SHORT_ROTATION(TradeDirection.SHORT),
    LONG_BREAKOUT(TradeDirection.LONG),
    SHORT_BREAKDOWN(TradeDirection.SHORT);

    private final TradeDirection direction;

    FutureArmType(TradeDirection direction) {
        this.direction = direction;
    }

    public TradeDirection direction() {
        return direction;
    }

    public boolean isLong() {
        return direction == TradeDirection.LONG;
    }

    /** Rotation arms fade inside the range (hurt by compression / whipsaw). */
    public boolean isRotation() {
        return this == LONG_ROTATION || this == SHORT_ROTATION;
    }

    /** Breakout/breakdown arms ride the range release (helped by compression). */
    public boolean isBreakout() {
        return this == LONG_BREAKOUT || this == SHORT_BREAKDOWN;
    }
}
