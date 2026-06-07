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
        BigDecimal oneFourSdBoundary
) {}
