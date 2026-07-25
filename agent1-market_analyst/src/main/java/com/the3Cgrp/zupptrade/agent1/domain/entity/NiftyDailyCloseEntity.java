package com.the3Cgrp.zupptrade.agent1.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One settled Nifty 50 close per trading day (nifty_daily_close).
 *
 * Written by Agent 1 as a byproduct of the 200-candle historical fetch it already
 * performs each scoring run — no extra Upstox call. Read by Agent 4 to grade signal
 * accuracy at expiry. The trade_date is the natural primary key (one row per day).
 */
@Entity
@Table(name = "nifty_daily_close")
public class NiftyDailyCloseEntity {

    @Id
    @Column(name = "trade_date", nullable = false, updatable = false)
    private LocalDate tradeDate;

    @Column(name = "close", nullable = false, precision = 10, scale = 2)
    private BigDecimal close;

    @Column(name = "source", nullable = false, length = 50)
    private String source = "UPSTOX_HISTORICAL";

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private LocalDateTime fetchedAt = LocalDateTime.now();

    public NiftyDailyCloseEntity() {}

    public NiftyDailyCloseEntity(LocalDate tradeDate, BigDecimal close) {
        this.tradeDate = tradeDate;
        this.close = close;
    }

    public LocalDate getTradeDate()          { return tradeDate; }
    public void setTradeDate(LocalDate v)    { this.tradeDate = v; }
    public BigDecimal getClose()             { return close; }
    public void setClose(BigDecimal v)       { this.close = v; }
    public String getSource()                { return source; }
    public void setSource(String v)          { this.source = v; }
    public LocalDateTime getFetchedAt()      { return fetchedAt; }
    public void setFetchedAt(LocalDateTime v){ this.fetchedAt = v; }
}
