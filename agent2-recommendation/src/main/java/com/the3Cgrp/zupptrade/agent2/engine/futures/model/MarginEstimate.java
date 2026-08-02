package com.the3Cgrp.zupptrade.agent2.engine.futures.model;

import java.math.BigDecimal;

/**
 * Recommend-time futures margin estimate (spec §6.8).
 * Config-driven % of notional — the authoritative Upstox /v2/charges/margin check
 * stays in Agent 5 immediately before the GTT fires.
 */
public record MarginEstimate(
        BigDecimal notional,
        BigDecimal marginPct,
        BigDecimal marginEstimate
) {}
