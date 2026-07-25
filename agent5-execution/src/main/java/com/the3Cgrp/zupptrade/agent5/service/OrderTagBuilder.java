package com.the3Cgrp.zupptrade.agent5.service;

import java.util.UUID;

/**
 * Builds Upstox order tags from a trade UUID.
 *
 * V3 order APIs have NO correlation_id — only a single {@code tag} field (max 40 chars). So the tag
 * now does the job correlation_id used to: it is UNIQUE PER LEG and maps an order back to its leg.
 * We place one leg per call (sequential execution) and query a leg's order(s) by its exact tag.
 *
 *   entry leg    ZUPP_{id8}_L{n}      e.g. ZUPP_A1B2C3D4_L0
 *   exit leg     ZUPP_{id8}_X_L{n}    e.g. ZUPP_A1B2C3D4_X_L0
 *   rollback     ZUPP_{id8}_RB_L{n}   e.g. ZUPP_A1B2C3D4_RB_L0
 *
 * All well under the 40-char tag limit (ZUPP_ = 5 + 8 hex + suffix ≈ 16–19 chars).
 */
public final class OrderTagBuilder {

    private OrderTagBuilder() {}

    /** Trade-level prefix shared by all of a trade's tags (not a tag itself). */
    private static String base(UUID tradeId) {
        return "ZUPP_" + tradeId.toString().substring(0, 8).toUpperCase();
    }

    /** Unique per-leg tag for an ENTRY order — replaces V2's correlation_id. */
    public static String entryTag(UUID tradeId, int legIndex) {
        return base(tradeId) + "_L" + legIndex;
    }

    /** Unique per-leg tag for an EXIT (reverse MARKET) order. */
    public static String exitTag(UUID tradeId, int legIndex) {
        return base(tradeId) + "_X_L" + legIndex;
    }

    /** Unique per-leg tag for a ROLLBACK / reconcile reverse order. */
    public static String rollbackTag(UUID tradeId, int seq) {
        return base(tradeId) + "_RB_L" + seq;
    }
}
