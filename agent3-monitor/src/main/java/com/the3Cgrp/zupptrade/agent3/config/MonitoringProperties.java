package com.the3Cgrp.zupptrade.agent3.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * All monitoring thresholds and tuning knobs in one place.
 * Values come from application.yml — never hardcoded.
 */
@ConfigurationProperties(prefix = "agent3.monitoring")
public class MonitoringProperties {

    // VIX thresholds
    private BigDecimal vixExtremeThreshold  = new BigDecimal("24");
    private BigDecimal vixSpikeIntraday     = new BigDecimal("0.30");  // 30% intraday change → recalculate PoP

    // PoP decision thresholds for credit spreads (0.0–1.0 scale)
    private BigDecimal popHoldMinimum       = new BigDecimal("0.80");
    private BigDecimal popWatchMinimum      = new BigDecimal("0.75");
    private BigDecimal popReadjustMinimum   = new BigDecimal("0.65");
    // Below popReadjustMinimum → EXIT

    // Debit spread theta exit (days to expiry)
    private int debitThetaExitDte = 2;

    // Expiry day (DTE=0) proximity buffers for credit spreads.
    // Black-Scholes is degenerate at DTE=0 (returns binary 1/0), so PoP ladder
    // is replaced with spot-distance checks on the final trading day.
    private int expiryDayExitBufferPts  = 75;   // EXIT when spot within this many pts of short strike
    private int expiryDayWatchBufferPts = 150;  // WATCH when spot within this many pts of short strike

    // Readjustment re-entry gates.
    // Relaxed G1 PoP is split by VIX level: stressed VIX uses a tighter floor.
    private BigDecimal readjustPopNormalVix    = new BigDecimal("65.0");  // PoP floor when VIX ≤ stress threshold
    private BigDecimal readjustPopStressedVix  = new BigDecimal("70.0");  // PoP floor when VIX > stress threshold
    private BigDecimal readjustVixStressThreshold = new BigDecimal("22"); // VIX boundary between the two PoP floors
    private int        readjustMinDteDays      = 1;  // DTE guard — no re-entry if DTE < this value

    // Risk-free rate for Black-Scholes
    private BigDecimal riskFreeRate = new BigDecimal("0.065");

    // Nifty instrument key for spot price
    private String niftyInstrumentKey = "NSE_INDEX|Nifty 50";

    // Scheduler — cron and NSE market hours (IST)
    private String schedulerCron       = "0 */5 9-15 * * MON-FRI";
    private int    marketOpenHour      = 9;
    private int    marketOpenMinute    = 15;
    private int    marketCloseHour     = 15;
    private int    marketCloseMinute   = 30;

    public BigDecimal getVixExtremeThreshold()  { return vixExtremeThreshold; }
    public void setVixExtremeThreshold(BigDecimal v) { this.vixExtremeThreshold = v; }

    public BigDecimal getVixSpikeIntraday()     { return vixSpikeIntraday; }
    public void setVixSpikeIntraday(BigDecimal v) { this.vixSpikeIntraday = v; }

    public BigDecimal getPopHoldMinimum()       { return popHoldMinimum; }
    public void setPopHoldMinimum(BigDecimal v) { this.popHoldMinimum = v; }

    public BigDecimal getPopWatchMinimum()      { return popWatchMinimum; }
    public void setPopWatchMinimum(BigDecimal v) { this.popWatchMinimum = v; }

    public BigDecimal getPopReadjustMinimum()   { return popReadjustMinimum; }
    public void setPopReadjustMinimum(BigDecimal v) { this.popReadjustMinimum = v; }

    public int getDebitThetaExitDte()              { return debitThetaExitDte; }
    public void setDebitThetaExitDte(int v)        { this.debitThetaExitDte = v; }

    public int getExpiryDayExitBufferPts()         { return expiryDayExitBufferPts; }
    public void setExpiryDayExitBufferPts(int v)   { this.expiryDayExitBufferPts = v; }

    public int getExpiryDayWatchBufferPts()        { return expiryDayWatchBufferPts; }
    public void setExpiryDayWatchBufferPts(int v)  { this.expiryDayWatchBufferPts = v; }

    public BigDecimal getReadjustPopNormalVix()       { return readjustPopNormalVix; }
    public void setReadjustPopNormalVix(BigDecimal v) { this.readjustPopNormalVix = v; }

    public BigDecimal getReadjustPopStressedVix()       { return readjustPopStressedVix; }
    public void setReadjustPopStressedVix(BigDecimal v) { this.readjustPopStressedVix = v; }

    public BigDecimal getReadjustVixStressThreshold()       { return readjustVixStressThreshold; }
    public void setReadjustVixStressThreshold(BigDecimal v) { this.readjustVixStressThreshold = v; }

    public int getReadjustMinDteDays()          { return readjustMinDteDays; }
    public void setReadjustMinDteDays(int v)    { this.readjustMinDteDays = v; }

    public BigDecimal getRiskFreeRate()         { return riskFreeRate; }
    public void setRiskFreeRate(BigDecimal v)   { this.riskFreeRate = v; }

    public String getNiftyInstrumentKey()       { return niftyInstrumentKey; }
    public void setNiftyInstrumentKey(String v) { this.niftyInstrumentKey = v; }

    public String getSchedulerCron()            { return schedulerCron; }
    public void setSchedulerCron(String v)      { this.schedulerCron = v; }

    public int getMarketOpenHour()              { return marketOpenHour; }
    public void setMarketOpenHour(int v)        { this.marketOpenHour = v; }

    public int getMarketOpenMinute()            { return marketOpenMinute; }
    public void setMarketOpenMinute(int v)      { this.marketOpenMinute = v; }

    public int getMarketCloseHour()             { return marketCloseHour; }
    public void setMarketCloseHour(int v)       { this.marketCloseHour = v; }

    public int getMarketCloseMinute()           { return marketCloseMinute; }
    public void setMarketCloseMinute(int v)     { this.marketCloseMinute = v; }
}
