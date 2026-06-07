package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;

import java.math.BigDecimal;

/**
 * Strategy Pattern — one implementation per scoring tier.
 * Every implementation must be idempotent and exception-safe.
 * Missing data → vote 0. Never throw from calculate().
 */
public interface TierScorer {

    TierScore calculate(MarketInputs inputs);

    String getTierName();

    BigDecimal getWeight();
}
