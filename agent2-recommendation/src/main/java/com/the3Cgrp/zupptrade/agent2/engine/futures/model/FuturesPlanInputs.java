package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import com.the3Cgrp.zupptrade.shared.enums.Bias;

import java.math.BigDecimal;
import java.util.List;

/**
 * All inputs the pure {@code FuturesPlanEngine} needs — assembled by the service from the
 * Agent 1 signal, user profile, and Upstox daily candles. Keeping this a plain record lets
 * the whole engine be unit-tested end-to-end without Spring or I/O.
 */
public record FuturesPlanInputs(
        BigDecimal priorHigh,
        BigDecimal priorLow,
        BigDecimal priorClose,
        BigDecimal openPx,
        Bias bias,
        BigDecimal confidenceScore,
        BigDecimal prevDayRange,
        List<BigDecimal> last20Ranges,
        int lotSize,
        BigDecimal capital
) {}
