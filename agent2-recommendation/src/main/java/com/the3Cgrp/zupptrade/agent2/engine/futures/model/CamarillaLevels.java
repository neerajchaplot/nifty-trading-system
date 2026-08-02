package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import java.math.BigDecimal;

/**
 * Deterministic Camarilla levels from prior-day OHLC (spec §2.1).
 * All scale-2 rupee points.
 */
public record CamarillaLevels(
        BigDecimal range,
        BigDecimal pivot,
        BigDecimal h3,
        BigDecimal h4,
        BigDecimal l3,
        BigDecimal l4
) {}
