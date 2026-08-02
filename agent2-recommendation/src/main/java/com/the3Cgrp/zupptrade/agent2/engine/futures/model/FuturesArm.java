package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.TradeDirection;

import java.math.BigDecimal;

/**
 * One arm's trigger/entry/stop/target level mapping (spec §2.3, §4 table).
 * These are the raw structural levels; the cost-aware R:R and sizing are computed downstream.
 */
public record FuturesArm(
        FutureArmType type,
        TradeDirection direction,
        BigDecimal triggerLevel,
        BigDecimal entry,
        BigDecimal stop,
        BigDecimal target
) {}
