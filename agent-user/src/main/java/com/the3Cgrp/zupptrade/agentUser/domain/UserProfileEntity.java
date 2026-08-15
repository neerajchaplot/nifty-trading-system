package com.the3Cgrp.zupptrade.agentUser.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_profiles", schema = "zupptrade_dev")
public class UserProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    /** UPSTOX (live) | GOOGLE (simulation). Nullable on legacy rows until backfilled. */
    @Column(name = "auth_provider", length = 10)
    private String authProvider;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "display_name", length = 120)
    private String displayName;

    /** SIMULATION | LIVE — gates real vs simulated order paths. */
    @Column(name = "account_mode", length = 12)
    private String accountMode;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    /** The admin profile's Upstox token serves shared market-data reads for simulation users. */
    @Column(name = "is_admin", nullable = false)
    private boolean admin = false;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal capital;

    @Column(name = "min_pop", nullable = false, precision = 4, scale = 2)
    private BigDecimal minPop;

    @Column(name = "max_loss_pct", nullable = false, precision = 4, scale = 2)
    private BigDecimal maxLossPct;

    @Column(name = "max_pop_popp_gap", nullable = false, precision = 4, scale = 2)
    private BigDecimal maxPopPoppGap;

    @Column(name = "spread_width_min", nullable = false)
    private int spreadWidthMin;

    @Column(name = "spread_width_max", nullable = false)
    private int spreadWidthMax;

    @Column(name = "min_roc_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal minRocPct;

    @Column(name = "tier1a_weight", nullable = false, precision = 5, scale = 4)
    private BigDecimal tier1aWeight;

    @Column(name = "tier1b_weight", nullable = false, precision = 5, scale = 4)
    private BigDecimal tier1bWeight;

    @Column(name = "tier2_weight", nullable = false, precision = 5, scale = 4)
    private BigDecimal tier2Weight;

    @Column(name = "tier3_weight", nullable = false, precision = 5, scale = 4)
    private BigDecimal tier3Weight;

    @Column(name = "tier4_weight", nullable = false, precision = 5, scale = 4)
    private BigDecimal tier4Weight;

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

    public UUID getId()                          { return id; }
    public String getUserId()                    { return userId; }
    public void setUserId(String v)              { this.userId = v; }
    public String getAuthProvider()              { return authProvider; }
    public void setAuthProvider(String v)        { this.authProvider = v; }
    public String getEmail()                     { return email; }
    public void setEmail(String v)               { this.email = v; }
    public String getDisplayName()               { return displayName; }
    public void setDisplayName(String v)         { this.displayName = v; }
    public String getAccountMode()               { return accountMode; }
    public void setAccountMode(String v)         { this.accountMode = v; }
    public String getStatus()                    { return status; }
    public void setStatus(String v)              { this.status = v; }
    public boolean isAdmin()                     { return admin; }
    public void setAdmin(boolean v)              { this.admin = v; }
    public BigDecimal getCapital()               { return capital; }
    public void setCapital(BigDecimal v)         { this.capital = v; }
    public BigDecimal getMinPop()                { return minPop; }
    public void setMinPop(BigDecimal v)          { this.minPop = v; }
    public BigDecimal getMaxLossPct()            { return maxLossPct; }
    public void setMaxLossPct(BigDecimal v)      { this.maxLossPct = v; }
    public BigDecimal getMaxPopPoppGap()         { return maxPopPoppGap; }
    public void setMaxPopPoppGap(BigDecimal v)   { this.maxPopPoppGap = v; }
    public int getSpreadWidthMin()               { return spreadWidthMin; }
    public void setSpreadWidthMin(int v)         { this.spreadWidthMin = v; }
    public int getSpreadWidthMax()               { return spreadWidthMax; }
    public void setSpreadWidthMax(int v)         { this.spreadWidthMax = v; }
    public BigDecimal getMinRocPct()             { return minRocPct; }
    public void setMinRocPct(BigDecimal v)       { this.minRocPct = v; }
    public BigDecimal getTier1aWeight()          { return tier1aWeight; }
    public void setTier1aWeight(BigDecimal v)    { this.tier1aWeight = v; }
    public BigDecimal getTier1bWeight()          { return tier1bWeight; }
    public void setTier1bWeight(BigDecimal v)    { this.tier1bWeight = v; }
    public BigDecimal getTier2Weight()           { return tier2Weight; }
    public void setTier2Weight(BigDecimal v)     { this.tier2Weight = v; }
    public BigDecimal getTier3Weight()           { return tier3Weight; }
    public void setTier3Weight(BigDecimal v)     { this.tier3Weight = v; }
    public BigDecimal getTier4Weight()           { return tier4Weight; }
    public void setTier4Weight(BigDecimal v)     { this.tier4Weight = v; }
    public LocalDateTime getCreatedAt()          { return createdAt; }
    public LocalDateTime getUpdatedAt()          { return updatedAt; }
}
