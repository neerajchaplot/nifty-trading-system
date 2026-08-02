package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;

/**
 * Outcome of one entry-FSM evaluation for a futures plan (spec §3).
 * {@code state} is the coarse status to persist; {@code fire} is true only on a fresh CONFIRMED
 * (entry trigger just met → hand to Agent 5).
 */
public record EntryDecision(
        FuturePlanStatus state,
        boolean fire,
        String reason
) {
    static EntryDecision of(FuturePlanStatus state, String reason) {
        return new EntryDecision(state, state == FuturePlanStatus.CONFIRMED, reason);
    }
}
