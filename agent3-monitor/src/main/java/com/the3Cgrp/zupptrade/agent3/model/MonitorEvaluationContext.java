package com.the3Cgrp.zupptrade.agent3.model;

import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;

import java.math.BigDecimal;

/**
 * All inputs required for one monitoring evaluation cycle.
 * Assembled by MonitorEvaluationService before delegating to the strategy.
 */
public record MonitorEvaluationContext(
        MonitorConfigDto config,
        LiveMarketSnapshot liveData,
        int currentDte,
        /** VIX from the most recent prior evaluation for this trade. Null on first evaluation. */
        BigDecimal previousVix,
        /** VIX at trade entry (from market_context JSONB on the trade). Null if unavailable. */
        BigDecimal entryVix
) {}
