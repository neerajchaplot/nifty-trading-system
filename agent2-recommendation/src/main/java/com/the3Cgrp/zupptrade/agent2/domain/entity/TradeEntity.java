package com.the3Cgrp.zupptrade.agent2.domain.entity;

import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "trades")
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent1_signal_id")
    private Agent1SignalEntity agent1Signal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_profile_id", nullable = false)
    private UserProfileEntity userProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Strategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "spread_direction", nullable = false)
    private SpreadDirection spreadDirection;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(nullable = false)
    private int dte;

    // JSONB columns — serialized/deserialized in service layer.
    // @JdbcTypeCode(SqlTypes.JSON) tells Hibernate 6 to bind as JSON rather than VARCHAR,
    // which PostgreSQL requires for jsonb columns.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String legs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "market_context", columnDefinition = "jsonb")
    private String marketContext;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String thresholds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monitor_config", columnDefinition = "jsonb")
    private String monitorConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gate_results", columnDefinition = "jsonb", nullable = false)
    private String gateResults;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_fills", columnDefinition = "jsonb")
    private String entryFills;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "valid_until", nullable = false)
    private LocalDateTime validUntil;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "trade_code", length = 20, unique = true, nullable = false)
    private String tradeCode;

    @Column(name = "close_reason")
    private String closeReason;

    @Column(name = "actual_pnl", precision = 12, scale = 2)
    private BigDecimal actualPnl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public Agent1SignalEntity getAgent1Signal() { return agent1Signal; }
    public void setAgent1Signal(Agent1SignalEntity agent1Signal) { this.agent1Signal = agent1Signal; }
    public UserProfileEntity getUserProfile() { return userProfile; }
    public void setUserProfile(UserProfileEntity userProfile) { this.userProfile = userProfile; }
    public TradeStatus getStatus() { return status; }
    public void setStatus(TradeStatus status) { this.status = status; }
    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy strategy) { this.strategy = strategy; }
    public SpreadDirection getSpreadDirection() { return spreadDirection; }
    public void setSpreadDirection(SpreadDirection spreadDirection) { this.spreadDirection = spreadDirection; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public int getDte() { return dte; }
    public void setDte(int dte) { this.dte = dte; }
    public String getLegs() { return legs; }
    public void setLegs(String legs) { this.legs = legs; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getMarketContext() { return marketContext; }
    public void setMarketContext(String marketContext) { this.marketContext = marketContext; }
    public String getThresholds() { return thresholds; }
    public void setThresholds(String thresholds) { this.thresholds = thresholds; }
    public String getMonitorConfig() { return monitorConfig; }
    public void setMonitorConfig(String monitorConfig) { this.monitorConfig = monitorConfig; }
    public String getGateResults() { return gateResults; }
    public void setGateResults(String gateResults) { this.gateResults = gateResults; }
    public String getEntryFills() { return entryFills; }
    public void setEntryFills(String entryFills) { this.entryFills = entryFills; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public String getTradeCode() { return tradeCode; }
    public void setTradeCode(String tradeCode) { this.tradeCode = tradeCode; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String closeReason) { this.closeReason = closeReason; }
    public BigDecimal getActualPnl() { return actualPnl; }
    public void setActualPnl(BigDecimal actualPnl) { this.actualPnl = actualPnl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
