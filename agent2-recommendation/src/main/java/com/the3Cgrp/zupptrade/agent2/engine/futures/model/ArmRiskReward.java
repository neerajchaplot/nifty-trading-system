package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import java.math.BigDecimal;

/**
 * R:R for one arm (spec §5). {@code rrAfterCost} is the value the two-gate validator checks.
 */
public record ArmRiskReward(
        BigDecimal riskPoints,
        BigDecimal rewardPoints,
        BigDecimal costPoints,
        BigDecimal rrGross,
        BigDecimal rrAfterCost
) {}
