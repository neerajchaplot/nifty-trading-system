package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import java.math.BigDecimal;

/**
 * The single hard gate for the futures engine (spec §5, revised): Agent 1 confidence only.
 *
 * There is NO R:R hard gate — intraday you cannot guarantee R:R at the actual fill, so
 * R:R and round-trip cost are computed and DISPLAYED for the user's judgement but never
 * block trade emission.
 */
public record ConfidenceGateResult(
        boolean passed,
        BigDecimal confidenceScore,
        BigDecimal minConfidence
) {}
