package com.the3Cgrp.zupptrade.agent1.domain.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Result of one tier scorer.
 * signals: signal name → vote (+1 bullish, 0 neutral, -1 bearish).
 * average: mean of all votes (missing signals score 0 — never excluded).
 * contribution: average × weight = this tier's input to the composite score.
 */
public record TierScore(
        String tierName,
        BigDecimal weight,
        Map<String, Integer> signals,
        BigDecimal average,
        BigDecimal contribution
) {}
