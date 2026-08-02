package com.the3Cgrp.zupptrade.agent2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Futures engine settings (spec §5–§6). All calibratable — the spec says validate weights
 * against Agent 4 before trusting them. Bound from {@code trading.futures.*} in application.yml.
 */
@ConfigurationProperties(prefix = "trading.futures")
public class FuturesConfig {

    /** §5 — the only hard gate: Agent 1 confidence must be ≥ this. */
    private BigDecimal minConfidence = new BigDecimal("0.40");

    /** §6.3 — RCI below this = compressed (blocks rotation arms only). */
    private BigDecimal compressionThreshold = new BigDecimal("0.70");

    /** §6.2 cost model (display-only) — slippage points per round trip. */
    private BigDecimal slippagePoints = new BigDecimal("2");
    /** §6.2 — flat brokerage + GST in ₹ per round trip (both legs). */
    private BigDecimal flatChargesPerRoundTrip = new BigDecimal("100");
    /** §6.2 — turnover tax fraction per round trip (STT/exchange/stamp). */
    private BigDecimal taxPctPerRoundTrip = new BigDecimal("0.0002");

    /** §6.8 — margin estimate as a fraction of notional (authoritative check in Agent 5). */
    private BigDecimal marginPct = new BigDecimal("0.12");

    /** §6.5 — fraction of capital risked per trade. */
    private BigDecimal riskPerTradePct = new BigDecimal("0.01");

    /** Trading-day lookback for the compression SMA(range,20) + prior-day OHLC. */
    private int compressionLookbackDays = 30;

    /** §6.6 kill-switch — max futures plans a user may commit (ARMED+) per day. */
    private int maxTradesPerDay = 3;

    /** reference_data key holding the resolved current-month Nifty futures instrument_key. */
    private String futInstrumentRefKey = "nifty.fut.current";

    public BigDecimal getMinConfidence() { return minConfidence; }
    public void setMinConfidence(BigDecimal v) { this.minConfidence = v; }
    public BigDecimal getCompressionThreshold() { return compressionThreshold; }
    public void setCompressionThreshold(BigDecimal v) { this.compressionThreshold = v; }
    public BigDecimal getSlippagePoints() { return slippagePoints; }
    public void setSlippagePoints(BigDecimal v) { this.slippagePoints = v; }
    public BigDecimal getFlatChargesPerRoundTrip() { return flatChargesPerRoundTrip; }
    public void setFlatChargesPerRoundTrip(BigDecimal v) { this.flatChargesPerRoundTrip = v; }
    public BigDecimal getTaxPctPerRoundTrip() { return taxPctPerRoundTrip; }
    public void setTaxPctPerRoundTrip(BigDecimal v) { this.taxPctPerRoundTrip = v; }
    public BigDecimal getMarginPct() { return marginPct; }
    public void setMarginPct(BigDecimal v) { this.marginPct = v; }
    public BigDecimal getRiskPerTradePct() { return riskPerTradePct; }
    public void setRiskPerTradePct(BigDecimal v) { this.riskPerTradePct = v; }
    public int getCompressionLookbackDays() { return compressionLookbackDays; }
    public void setCompressionLookbackDays(int v) { this.compressionLookbackDays = v; }
    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(int v) { this.maxTradesPerDay = v; }
    public String getFutInstrumentRefKey() { return futInstrumentRefKey; }
    public void setFutInstrumentRefKey(String v) { this.futInstrumentRefKey = v; }
}
