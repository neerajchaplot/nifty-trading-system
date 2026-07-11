package com.the3Cgrp.zupptrade.shared.constants;

import java.math.BigDecimal;

/**
 * Compile-time constants shared across agents.
 * Tunable market parameters (VIX thresholds, flow thresholds) live in each agent's
 * application.yml instead — only values that are truly cross-agent invariants belong here.
 */
public final class TradingConstants {

    private TradingConstants() {}

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final BigDecimal RISK_FREE_RATE = new BigDecimal("0.065");

    // PoP ladder thresholds — used by Agent 2 to compute indicative T1/T2 Nifty levels
    // and by Agent 3 (MonitoringProperties) as the runtime PoP evaluation ladder.
    // If Agent 3's application.yml overrides these, update here too so Agent 2's
    // computed levels remain consistent with what Agent 3 will actually trigger on.
    public static final double POP_HOLD_MINIMUM     = 0.80;  // PoP drops below → WATCH
    public static final double POP_WATCH_MINIMUM    = 0.75;  // PoP drops below → READJUST
    public static final double POP_READJUST_MINIMUM = 0.65;  // PoP drops below → EXIT
}
