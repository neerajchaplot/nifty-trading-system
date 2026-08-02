package com.the3Cgrp.zupptrade.shared.util;

import java.util.UUID;

/**
 * Deterministic broker order tag for a futures plan: {@code ZUPP_{planId8}} (first 8 hex of the
 * plan UUID, uppercased). Same scheme as the options path (ZUPP_{id8}).
 *
 * The tag is the linkage between our plan and the broker's orders: Agent 5 places the GTT with
 * this tag, and Agent 4 later queries Upstox by the same tag to compute realized P&L. Because it
 * is derived (not stored), both sides MUST build it here so they always match.
 */
public final class FuturesTag {

    private static final String PREFIX = "ZUPP_";

    private FuturesTag() {}

    /** @return e.g. "ZUPP_A1B2C3D4" for plan a1b2c3d4-…. */
    public static String of(UUID planId) {
        return PREFIX + planId.toString().substring(0, 8).toUpperCase();
    }
}
