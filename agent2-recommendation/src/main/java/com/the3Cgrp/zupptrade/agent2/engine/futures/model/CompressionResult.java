package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import java.math.BigDecimal;

/**
 * Compression gate (spec §6.3): RCI = range(t-1) / SMA(range,20).
 * {@code compressed == true} → NO_TRADE (whipsaw risk exceeds edge).
 */
public record CompressionResult(
        BigDecimal rci,
        BigDecimal threshold,
        boolean compressed
) {}
