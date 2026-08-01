package com.the3Cgrp.zupptrade.agent5.dto;

/**
 * Simulation fault modes for the offline synthetic execution path (simulate-fills /
 * simulate-exit). Supplied per call via the {@code X-Sim-Fill-Mode} request header.
 *
 * IGNORED entirely unless {@code simulate-fills} / {@code simulate-exit} is enabled
 * (sandbox / simulation config) — the production order path never consults it, so a stray
 * header can never affect a real order.
 *
 * Entry ({@code /execute}):
 *   FILL             fills perfectly at limitPrice            → ACTIVE, no slippage
 *   SLIPPAGE         fills worse than limit                   → ACTIVE, slippageAlert = true
 *   PARTIAL_ROLLBACK one leg rejected, filled leg rolled back → REJECTED, no position
 *   TIMEOUT_MARKET   resting LIMIT times out → MARKET fill    → ACTIVE, or REJECTED (cancelled)
 *                    depending on cancel-on-timeout-instead-of-market
 *   MARGIN_REJECT    margin insufficient                      → REJECTED, no position
 *
 * Exit ({@code /exit}): FILL / SLIPPAGE → normal CLOSED; any other mode → simulated EXIT_FAILED
 * (so the Agent 3 retry path can be exercised).
 */
public enum SimFillMode {
    FILL,
    SLIPPAGE,
    PARTIAL_ROLLBACK,
    TIMEOUT_MARKET,
    MARGIN_REJECT
}
