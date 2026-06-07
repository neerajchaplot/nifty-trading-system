package com.the3Cgrp.zupptrade.agent1.domain.model;

import java.math.BigDecimal;

/**
 * 5-day trend summary for FII index futures net flow.
 *
 * Per CLAUDE.md Tier 2: "store trend separately in score_breakdown JSONB.
 * Do not change the score but note it for confidence."
 *
 * Included in raw_inputs JSONB (not in scoring signals) so it is auditable
 * and visible to the user without diluting the Tier 2 average.
 *
 * direction:
 *   IMPROVING     — FII net futures was positive on ≥4 of the last 5 trading days
 *   DETERIORATING — FII net futures was negative on ≥4 of the last 5 trading days
 *   FLAT          — mixed or insufficient data
 *   UNAVAILABLE   — no snapshots in DB yet (first run)
 */
public record FiiDiiTrend(
        String direction,
        BigDecimal avgNetFlow5d,   // average net_flow over snapshotCount days, ₹Cr
        int daysPositive,          // days with net_flow > 0
        int daysNegative,          // days with net_flow < 0
        int snapshotCount          // actual rows used (≤5; <5 means partial history)
) {
    /** Returned when no DB history is available (first scoring run). */
    public static FiiDiiTrend unavailable() {
        return new FiiDiiTrend("UNAVAILABLE", null, 0, 0, 0);
    }
}
