package com.the3Cgrp.zupptrade.agent1.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per trading session per segment in fii_dii_snapshots.
 *
 * Segments:
 *   NSE_FO|INDEX_FUTURES — populated by UpstoxFiiDiiClient from /v2/market/fii
 *   NSE_FO|INDEX_OPTIONS — populated by UpstoxFiiDiiClient from /v2/market/fii
 *   NSE_EQ|CASH          — populated by UpstoxFiiDiiClient from /v2/market/dii
 *
 * Unique constraint on (trading_date, segment) prevents duplicate rows —
 * the unique constraint acts as a natural upsert guard.
 */
@Entity
@Table(name = "fii_dii_snapshots")
public class FiiDiiSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    /** NSE_FO|INDEX_FUTURES, NSE_FO|INDEX_OPTIONS, or NSE_EQ|CASH */
    @Column(name = "segment", nullable = false, length = 30)
    private String segment;

    @Column(name = "buy_amount", precision = 15, scale = 2)
    private BigDecimal buyAmount;

    @Column(name = "sell_amount", precision = 15, scale = 2)
    private BigDecimal sellAmount;

    /** buy_amount − sell_amount; computed by FiiDiiService on insert */
    @Column(name = "net_flow", precision = 15, scale = 2)
    private BigDecimal netFlow;

    @Column(name = "buy_contracts")
    private Long buyContracts;

    @Column(name = "sell_contracts")
    private Long sellContracts;

    @Column(name = "oi_contracts")
    private Long oiContracts;

    @Column(name = "oi_amount", precision = 15, scale = 2)
    private BigDecimal oiAmount;

    /** Cumulative FII long OI positions — populated for INDEX_FUTURES only */
    @Column(name = "total_long_contracts")
    private Long totalLongContracts;

    @Column(name = "total_short_contracts")
    private Long totalShortContracts;

    /** total_long / (total_long + total_short) — populated for INDEX_FUTURES only */
    @Column(name = "long_ratio", precision = 6, scale = 4)
    private BigDecimal longRatio;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private LocalDateTime fetchedAt = LocalDateTime.now();

    public FiiDiiSnapshotEntity() {}

    public UUID getId()                            { return id; }
    public LocalDate getTradingDate()              { return tradingDate; }
    public void setTradingDate(LocalDate v)        { this.tradingDate = v; }
    public String getSegment()                     { return segment; }
    public void setSegment(String v)               { this.segment = v; }
    public BigDecimal getBuyAmount()               { return buyAmount; }
    public void setBuyAmount(BigDecimal v)         { this.buyAmount = v; }
    public BigDecimal getSellAmount()              { return sellAmount; }
    public void setSellAmount(BigDecimal v)        { this.sellAmount = v; }
    public BigDecimal getNetFlow()                 { return netFlow; }
    public void setNetFlow(BigDecimal v)           { this.netFlow = v; }
    public Long getBuyContracts()                  { return buyContracts; }
    public void setBuyContracts(Long v)            { this.buyContracts = v; }
    public Long getSellContracts()                 { return sellContracts; }
    public void setSellContracts(Long v)           { this.sellContracts = v; }
    public Long getOiContracts()                   { return oiContracts; }
    public void setOiContracts(Long v)             { this.oiContracts = v; }
    public BigDecimal getOiAmount()                { return oiAmount; }
    public void setOiAmount(BigDecimal v)          { this.oiAmount = v; }
    public Long getTotalLongContracts()            { return totalLongContracts; }
    public void setTotalLongContracts(Long v)      { this.totalLongContracts = v; }
    public Long getTotalShortContracts()           { return totalShortContracts; }
    public void setTotalShortContracts(Long v)     { this.totalShortContracts = v; }
    public BigDecimal getLongRatio()               { return longRatio; }
    public void setLongRatio(BigDecimal v)         { this.longRatio = v; }
    public LocalDateTime getFetchedAt()            { return fetchedAt; }
    public void setFetchedAt(LocalDateTime v)      { this.fetchedAt = v; }
}
