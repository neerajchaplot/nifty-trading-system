package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import java.math.BigDecimal;

/**
 * Position sizing (spec §6.5): lots = riskCapital / (stopPoints × pointValuePerLot).
 */
public record SizingResult(
        int lots,
        int lotSize,
        BigDecimal stopPoints,
        BigDecimal pointValuePerLot,
        BigDecimal riskCapital,
        BigDecimal riskPerLot,
        BigDecimal riskTotal
) {}
