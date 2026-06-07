package com.the3Cgrp.zupptrade.agent2.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", unique = true, nullable = false, length = 50)
    private String userId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal capital;

    @Column(name = "min_pop", nullable = false, precision = 4, scale = 2)
    private BigDecimal minPop;

    @Column(name = "max_pop_popp_gap", nullable = false, precision = 4, scale = 2)
    private BigDecimal maxPopPoppGap;

    @Column(name = "max_loss_pct", nullable = false, precision = 4, scale = 2)
    private BigDecimal maxLossPct;

    @Column(name = "spread_width_min", nullable = false)
    private int spreadWidthMin;

    @Column(name = "spread_width_max", nullable = false)
    private int spreadWidthMax;

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
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public BigDecimal getCapital() { return capital; }
    public void setCapital(BigDecimal capital) { this.capital = capital; }
    public BigDecimal getMinPop() { return minPop; }
    public void setMinPop(BigDecimal minPop) { this.minPop = minPop; }
    public BigDecimal getMaxPopPoppGap() { return maxPopPoppGap; }
    public void setMaxPopPoppGap(BigDecimal maxPopPoppGap) { this.maxPopPoppGap = maxPopPoppGap; }
    public BigDecimal getMaxLossPct() { return maxLossPct; }
    public void setMaxLossPct(BigDecimal maxLossPct) { this.maxLossPct = maxLossPct; }
    public int getSpreadWidthMin() { return spreadWidthMin; }
    public void setSpreadWidthMin(int spreadWidthMin) { this.spreadWidthMin = spreadWidthMin; }
    public int getSpreadWidthMax() { return spreadWidthMax; }
    public void setSpreadWidthMax(int spreadWidthMax) { this.spreadWidthMax = spreadWidthMax; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
