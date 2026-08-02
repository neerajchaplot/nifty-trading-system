package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ConfidenceGateResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The futures engine's single hard gate (spec §5, revised): Agent 1 confidence ≥ minConfidence
 * (default 0.40; Low blocked unless 09:31-confirmed at half size).
 *
 * R:R is NOT gated intraday — {@link RiskRewardCalculator} still computes gross/after-cost R:R
 * and {@link CostModel} the round-trip cost, but both are display-only for the user's decision.
 */
@Component
public class ConfidenceGate {

    public ConfidenceGateResult validate(BigDecimal confidenceScore, BigDecimal minConfidence) {
        boolean passed = confidenceScore != null && confidenceScore.compareTo(minConfidence) >= 0;
        return new ConfidenceGateResult(passed, confidenceScore, minConfidence);
    }
}
