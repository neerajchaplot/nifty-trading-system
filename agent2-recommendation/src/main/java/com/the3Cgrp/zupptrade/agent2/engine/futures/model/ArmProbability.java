package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;

import java.math.BigDecimal;

/**
 * Relative probability ranking for one arm (spec §2.5).
 * Published as a ranking, not literal odds, until Agent 4 calibrates.
 */
public record ArmProbability(
        FutureArmType type,
        BigDecimal rawScore,
        BigDecimal probabilityPct
) {}
