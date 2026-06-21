package com.the3Cgrp.zupptrade.agent3.domain.entity;

import com.the3Cgrp.zupptrade.shared.enums.MonitorAction;
import com.the3Cgrp.zupptrade.shared.enums.ThresholdHit;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "monitoring_evaluations")
public class MonitoringEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trade_id", nullable = false)
    private UUID tradeId;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MonitorAction action;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "threshold_hit")
    private ThresholdHit thresholdHit;

    @Column(name = "spot_price", precision = 10, scale = 2)
    private BigDecimal spotPrice;

    @Column(name = "vix_level", precision = 6, scale = 2)
    private BigDecimal vixLevel;

    @Column(name = "current_pop", precision = 8, scale = 6)
    private BigDecimal currentPop;

    @Column(name = "current_net_premium", precision = 10, scale = 4)
    private BigDecimal currentNetPremium;

    @Column(name = "mark_to_market_pnl", precision = 12, scale = 2)
    private BigDecimal markToMarketPnl;

    @Column(name = "short_leg_ltp", precision = 10, scale = 2)
    private BigDecimal shortLegLtp;

    @Column(name = "long_leg_ltp", precision = 10, scale = 2)
    private BigDecimal longLegLtp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_detail", columnDefinition = "jsonb")
    private String evaluationDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (evaluatedAt == null) {
            evaluatedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getTradeId() { return tradeId; }
    public void setTradeId(UUID tradeId) { this.tradeId = tradeId; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Instant evaluatedAt) { this.evaluatedAt = evaluatedAt; }
    public MonitorAction getAction() { return action; }
    public void setAction(MonitorAction action) { this.action = action; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public ThresholdHit getThresholdHit() { return thresholdHit; }
    public void setThresholdHit(ThresholdHit thresholdHit) { this.thresholdHit = thresholdHit; }
    public BigDecimal getSpotPrice() { return spotPrice; }
    public void setSpotPrice(BigDecimal spotPrice) { this.spotPrice = spotPrice; }
    public BigDecimal getVixLevel() { return vixLevel; }
    public void setVixLevel(BigDecimal vixLevel) { this.vixLevel = vixLevel; }
    public BigDecimal getCurrentPop() { return currentPop; }
    public void setCurrentPop(BigDecimal currentPop) { this.currentPop = currentPop; }
    public BigDecimal getCurrentNetPremium() { return currentNetPremium; }
    public void setCurrentNetPremium(BigDecimal currentNetPremium) { this.currentNetPremium = currentNetPremium; }
    public BigDecimal getMarkToMarketPnl() { return markToMarketPnl; }
    public void setMarkToMarketPnl(BigDecimal markToMarketPnl) { this.markToMarketPnl = markToMarketPnl; }
    public BigDecimal getShortLegLtp() { return shortLegLtp; }
    public void setShortLegLtp(BigDecimal shortLegLtp) { this.shortLegLtp = shortLegLtp; }
    public BigDecimal getLongLegLtp() { return longLegLtp; }
    public void setLongLegLtp(BigDecimal longLegLtp) { this.longLegLtp = longLegLtp; }
    public String getEvaluationDetail() { return evaluationDetail; }
    public void setEvaluationDetail(String evaluationDetail) { this.evaluationDetail = evaluationDetail; }
    public Instant getCreatedAt() { return createdAt; }
}
