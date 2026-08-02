package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.OpenZone;

import java.util.List;
import java.util.Optional;

/**
 * Complete output of the futures engine: levels, open zone, all four sized arms, the selected
 * primary (if any), gate + compression outcomes, and the plan-level NO_TRADE verdict.
 */
public record FuturesPlanResult(
        CamarillaLevels camarilla,
        OpenZone openZone,
        List<ArmPlan> arms,
        FutureArmType primaryArm,
        boolean rangeFade,
        String selectionReason,
        CompressionResult compression,
        ConfidenceGateResult confidenceGate,
        boolean planNoTrade,
        String planNoTradeReason
) {
    public Optional<ArmPlan> primary() {
        if (primaryArm == null) return Optional.empty();
        return arms.stream().filter(a -> a.arm().type() == primaryArm).findFirst();
    }
}
