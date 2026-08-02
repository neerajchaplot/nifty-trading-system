package com.the3Cgrp.zupptrade.agent2.domain.entity;

import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import com.the3Cgrp.zupptrade.shared.enums.OpenZone;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Single evolving-row plan-of-record for a futures trade (table trade_future_ledger, V116).
 * Doubles as the §6.1 signal logger. JSONB columns are serialized in the service layer,
 * mirroring {@link TradeEntity}.
 */
@Entity
@Table(name = "trade_future_ledger")
public class FutureTradeLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_code", length = 24, unique = true, nullable = false)
    private String planCode;

    @Column(name = "agent1_signal_id")
    private UUID agent1SignalId;

    @Column(name = "user_profile_id", nullable = false)
    private UUID userProfileId;

    @Column(name = "run_phase", nullable = false)
    private int runPhase;

    @Column(name = "instrument_key", length = 64)
    private String instrumentKey;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Bias bias;

    @Column(name = "confidence_score", precision = 4, scale = 2)
    private BigDecimal confidenceScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_label", length = 10)
    private Confidence confidenceLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "open_zone", length = 10)
    private OpenZone openZone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prior_ohlc", columnDefinition = "jsonb", nullable = false)
    private String priorOhlc;

    @Column(name = "open_px", precision = 10, scale = 2)
    private BigDecimal openPx;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String camarilla;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "four_arms", columnDefinition = "jsonb", nullable = false)
    private String fourArms;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_arm", length = 20)
    private FutureArmType primaryArm;

    @Column(name = "entry_price", precision = 10, scale = 2)
    private BigDecimal entryPrice;

    @Column(name = "stop_price", precision = 10, scale = 2)
    private BigDecimal stopPrice;

    @Column(name = "target_price", precision = 10, scale = 2)
    private BigDecimal targetPrice;

    @Column(name = "rr_primary", precision = 5, scale = 2)
    private BigDecimal rrPrimary;

    @Column(name = "rr_after_cost", precision = 5, scale = 2)
    private BigDecimal rrAfterCost;

    @Column(name = "compression_rci", precision = 6, scale = 3)
    private BigDecimal compressionRci;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String sizing;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gate_results", columnDefinition = "jsonb")
    private String gateResults;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FuturePlanStatus status;

    @Column(name = "no_trade_reason", length = 200)
    private String noTradeReason;

    @Column(name = "gtt_order_id", length = 64)
    private String gttOrderId;

    @Column(name = "fill_price", precision = 10, scale = 2)
    private BigDecimal fillPrice;

    @Column(name = "realized_pnl", precision = 12, scale = 2)
    private BigDecimal realizedPnl;

    @Column(name = "close_reason", length = 100)
    private String closeReason;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }
    public UUID getAgent1SignalId() { return agent1SignalId; }
    public void setAgent1SignalId(UUID agent1SignalId) { this.agent1SignalId = agent1SignalId; }
    public UUID getUserProfileId() { return userProfileId; }
    public void setUserProfileId(UUID userProfileId) { this.userProfileId = userProfileId; }
    public int getRunPhase() { return runPhase; }
    public void setRunPhase(int runPhase) { this.runPhase = runPhase; }
    public String getInstrumentKey() { return instrumentKey; }
    public void setInstrumentKey(String instrumentKey) { this.instrumentKey = instrumentKey; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public Bias getBias() { return bias; }
    public void setBias(Bias bias) { this.bias = bias; }
    public BigDecimal getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(BigDecimal confidenceScore) { this.confidenceScore = confidenceScore; }
    public Confidence getConfidenceLabel() { return confidenceLabel; }
    public void setConfidenceLabel(Confidence confidenceLabel) { this.confidenceLabel = confidenceLabel; }
    public OpenZone getOpenZone() { return openZone; }
    public void setOpenZone(OpenZone openZone) { this.openZone = openZone; }
    public String getPriorOhlc() { return priorOhlc; }
    public void setPriorOhlc(String priorOhlc) { this.priorOhlc = priorOhlc; }
    public BigDecimal getOpenPx() { return openPx; }
    public void setOpenPx(BigDecimal openPx) { this.openPx = openPx; }
    public String getCamarilla() { return camarilla; }
    public void setCamarilla(String camarilla) { this.camarilla = camarilla; }
    public String getFourArms() { return fourArms; }
    public void setFourArms(String fourArms) { this.fourArms = fourArms; }
    public FutureArmType getPrimaryArm() { return primaryArm; }
    public void setPrimaryArm(FutureArmType primaryArm) { this.primaryArm = primaryArm; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public BigDecimal getStopPrice() { return stopPrice; }
    public void setStopPrice(BigDecimal stopPrice) { this.stopPrice = stopPrice; }
    public BigDecimal getTargetPrice() { return targetPrice; }
    public void setTargetPrice(BigDecimal targetPrice) { this.targetPrice = targetPrice; }
    public BigDecimal getRrPrimary() { return rrPrimary; }
    public void setRrPrimary(BigDecimal rrPrimary) { this.rrPrimary = rrPrimary; }
    public BigDecimal getRrAfterCost() { return rrAfterCost; }
    public void setRrAfterCost(BigDecimal rrAfterCost) { this.rrAfterCost = rrAfterCost; }
    public BigDecimal getCompressionRci() { return compressionRci; }
    public void setCompressionRci(BigDecimal compressionRci) { this.compressionRci = compressionRci; }
    public String getSizing() { return sizing; }
    public void setSizing(String sizing) { this.sizing = sizing; }
    public String getGateResults() { return gateResults; }
    public void setGateResults(String gateResults) { this.gateResults = gateResults; }
    public FuturePlanStatus getStatus() { return status; }
    public void setStatus(FuturePlanStatus status) { this.status = status; }
    public String getNoTradeReason() { return noTradeReason; }
    public void setNoTradeReason(String noTradeReason) { this.noTradeReason = noTradeReason; }
    public String getGttOrderId() { return gttOrderId; }
    public void setGttOrderId(String gttOrderId) { this.gttOrderId = gttOrderId; }
    public BigDecimal getFillPrice() { return fillPrice; }
    public void setFillPrice(BigDecimal fillPrice) { this.fillPrice = fillPrice; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String closeReason) { this.closeReason = closeReason; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime approvedAt) { this.approvedAt = approvedAt; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(OffsetDateTime activatedAt) { this.activatedAt = activatedAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(OffsetDateTime closedAt) { this.closedAt = closedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
