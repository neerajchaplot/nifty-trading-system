package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;

import java.util.List;

/**
 * Result of collapsing the four-arm grid to a decision (spec §2.4).
 *
 * @param primary      the single surviving arm; null for range-fade or no-trade
 * @param alternatives the other published arms (ranking context)
 * @param rangeFade    true for a Neutral bias → fade both rotation arms (primary is null)
 * @param noTrade      true when bias and open location conflict (e.g. bullish bias, open broke down)
 * @param reason       audit reason for rangeFade / noTrade (null otherwise)
 */
public record ArmSelection(
        FutureArmType primary,
        List<FutureArmType> alternatives,
        boolean rangeFade,
        boolean noTrade,
        String reason
) {
    public static ArmSelection primary(FutureArmType primary, List<FutureArmType> alternatives) {
        return new ArmSelection(primary, alternatives, false, false, null);
    }

    public static ArmSelection rangeFade(List<FutureArmType> arms, String reason) {
        return new ArmSelection(null, arms, true, false, reason);
    }

    public static ArmSelection noTrade(String reason) {
        return new ArmSelection(null, List.of(), false, true, reason);
    }
}
