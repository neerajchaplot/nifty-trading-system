package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import com.the3Cgrp.zupptrade.shared.enums.ArmCardStatus;

import java.math.BigDecimal;

/**
 * A fully-costed, sized single arm — everything the UI needs to render one selectable trade.
 */
public record ArmPlan(
        FuturesArm arm,
        ArmRiskReward rr,
        BigDecimal probabilityPct,
        SizingResult sizing,
        MarginEstimate margin,
        ArmCardStatus status,
        String blockedReason
) {}
