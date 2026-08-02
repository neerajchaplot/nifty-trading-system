package com.the3Cgrp.zupptrade.agent3.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

/**
 * Futures entry-watcher settings (spec §3). Bound from {@code agent3.futures.*}. All calibratable.
 */
@ConfigurationProperties(prefix = "agent3.futures")
public class FuturesEntryProperties {

    /** Master switch for the futures entry scheduler. */
    private boolean enabled = true;

    /** Candle interval to evaluate closes on. */
    private int candleIntervalMinutes = 5;

    /** Consecutive closes beyond the trigger required to confirm entry. */
    private int requiredConsecutiveCloses = 2;

    /** No-confirm cutoff — plans still unconfirmed at/after this time EXPIRE. */
    private LocalTime cutoffTime = LocalTime.of(11, 0);

    /** IST — the container runs UTC but the trading window is Asia/Kolkata. */
    private String zone = "Asia/Kolkata";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getCandleIntervalMinutes() { return candleIntervalMinutes; }
    public void setCandleIntervalMinutes(int v) { this.candleIntervalMinutes = v; }
    public int getRequiredConsecutiveCloses() { return requiredConsecutiveCloses; }
    public void setRequiredConsecutiveCloses(int v) { this.requiredConsecutiveCloses = v; }
    public LocalTime getCutoffTime() { return cutoffTime; }
    public void setCutoffTime(LocalTime cutoffTime) { this.cutoffTime = cutoffTime; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
}
