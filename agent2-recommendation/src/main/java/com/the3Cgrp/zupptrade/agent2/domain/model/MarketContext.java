package com.the3Cgrp.zupptrade.agent2.domain.model;

import com.the3Cgrp.zupptrade.shared.enums.IvRegime;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;

import java.math.BigDecimal;

public record MarketContext(
        BigDecimal spot,
        BigDecimal vix,
        BigDecimal atmIv,
        BigDecimal historicalVolatility,
        BigDecimal ivHvRatio,
        IvRegime ivRegime,
        VixRegime vixRegime,
        BigDecimal expectedMove,
        BigDecimal oneFourSdBoundary,
        // True when this trade was opened as a readjustment re-entry (relaxed G1 gate). Agent 3 uses
        // this to make a readjusted trade EXIT-only — it is never readjusted a second time.
        boolean readjustmentEntry
) {}
