package com.the3Cgrp.zupptrade.agent5.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Agent 5 execution behaviour config — bound from agent5.execution.*.
 *
 * Upstox connectivity (URLs, tokens, timeouts) is NOT here.
 * Those come from UpstoxProperties (core-module) bound at upstox.api.*.
 */
@ConfigurationProperties(prefix = "agent5.execution")
public class Agent5ExecutionProperties {

    /** Poll interval for order fill status checks (ms). */
    private int fillPollIntervalMs = 5000;

    /**
     * Time to wait for a LIMIT order to fill before action (ms).
     * After timeout: convert to MARKET (or cancel, per cancelOnTimeoutInsteadOfMarket).
     */
    private int fillTimeoutMs = 30000;

    /**
     * Slippage alert threshold as a fraction of expected net premium.
     * 0.10 = alert when actual net < expected × 0.90. Trade stays live — warning only.
     */
    private BigDecimal slippageAlertThreshold = new BigDecimal("0.10");

    /**
     * Upstox product type for Nifty options.
     * D = Delivery/NRML — holds overnight across multiple days (correct for weekly spreads).
     * NEVER use I (Intraday) — broker auto-squares at 3:20 PM.
     */
    private String product = "D";

    /**
     * When true: cancel unfilled orders after timeout instead of converting to MARKET.
     * Default false (production): auto-convert ensures fills on liquid NIFTY strikes.
     * Set true in sandbox profile: sandbox fills are synthetic, avoid unintended MARKET orders.
     */
    private boolean cancelOnTimeoutInsteadOfMarket = false;

    /**
     * When true: skip the margin sufficiency check entirely and proceed to order placement.
     * Use ONLY in sandbox profile to test execution flow without a funded Upstox account.
     * NEVER set true in production — you will place orders without verifying available funds.
     */
    private boolean bypassMarginCheck = false;

    /**
     * When true: skip Upstox order placement entirely and inject synthetic fills at limitPrice.
     * Use ONLY in sandbox profile — Upstox sandbox does not carry weekly NIFTY option contracts.
     * NEVER set true in production — no real orders will be placed.
     */
    private boolean simulateFills = false;

    /**
     * When true: skip Upstox exit order placement and mark the trade CLOSED directly.
     * Use ONLY in sandbox profile — mirrors simulate-fills for the exit path.
     * NEVER set true in production — no exit orders will be placed.
     */
    private boolean simulateExit = false;

    public int getFillPollIntervalMs() { return fillPollIntervalMs; }
    public void setFillPollIntervalMs(int v) { this.fillPollIntervalMs = v; }

    public int getFillTimeoutMs() { return fillTimeoutMs; }
    public void setFillTimeoutMs(int v) { this.fillTimeoutMs = v; }

    public BigDecimal getSlippageAlertThreshold() { return slippageAlertThreshold; }
    public void setSlippageAlertThreshold(BigDecimal v) { this.slippageAlertThreshold = v; }

    public String getProduct() { return product; }
    public void setProduct(String v) { this.product = v; }

    public boolean isCancelOnTimeoutInsteadOfMarket() { return cancelOnTimeoutInsteadOfMarket; }
    public void setCancelOnTimeoutInsteadOfMarket(boolean v) { this.cancelOnTimeoutInsteadOfMarket = v; }

    public boolean isBypassMarginCheck() { return bypassMarginCheck; }
    public void setBypassMarginCheck(boolean v) { this.bypassMarginCheck = v; }

    public boolean isSimulateFills() { return simulateFills; }
    public void setSimulateFills(boolean v) { this.simulateFills = v; }

    public boolean isSimulateExit() { return simulateExit; }
    public void setSimulateExit(boolean v) { this.simulateExit = v; }
}
