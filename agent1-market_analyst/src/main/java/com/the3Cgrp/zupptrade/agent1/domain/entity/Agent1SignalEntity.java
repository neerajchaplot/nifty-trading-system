package com.the3Cgrp.zupptrade.agent1.domain.entity;

import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent1_signals")
public class Agent1SignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Bias bias;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Strength strength;

    @Column(name = "composite_score", nullable = false, precision = 6, scale = 4)
    private BigDecimal compositeScore;

    /** Numeric confidence 0–1, stored in `confidence` column. */
    @Column(name = "confidence", nullable = false, precision = 4, scale = 2)
    private BigDecimal confidenceScore;

    /** Enum label stored in `confidence_label` column — read by Agent 2 StrategySelector. */
    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_label", nullable = false, length = 10)
    private Confidence confidence;

    @Column(name = "vix_level", precision = 6, scale = 2)
    private BigDecimal vixLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "vix_regime", length = 10)
    private VixRegime vixRegime;

    @Column(name = "vix_direction", length = 10)
    private String vixDirection;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_breakdown", columnDefinition = "jsonb")
    private String scoreBreakdown;

    @Column(name = "commentary_divergence", nullable = false)
    private Boolean commentaryDivergence = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_levels", columnDefinition = "jsonb")
    private String keyLevels;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_inputs", columnDefinition = "jsonb")
    private String rawInputs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_gaps", columnDefinition = "jsonb")
    private String dataGaps;

    @Column(nullable = false, length = 10)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public UUID getId() { return id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime v) { this.timestamp = v; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate v) { this.expiryDate = v; }
    public Bias getBias() { return bias; }
    public void setBias(Bias v) { this.bias = v; }
    public Strength getStrength() { return strength; }
    public void setStrength(Strength v) { this.strength = v; }
    public BigDecimal getCompositeScore() { return compositeScore; }
    public void setCompositeScore(BigDecimal v) { this.compositeScore = v; }
    public BigDecimal getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(BigDecimal v) { this.confidenceScore = v; }
    public Confidence getConfidence() { return confidence; }
    public void setConfidence(Confidence v) { this.confidence = v; }
    public BigDecimal getVixLevel() { return vixLevel; }
    public void setVixLevel(BigDecimal v) { this.vixLevel = v; }
    public VixRegime getVixRegime() { return vixRegime; }
    public void setVixRegime(VixRegime v) { this.vixRegime = v; }
    public String getVixDirection() { return vixDirection; }
    public void setVixDirection(String v) { this.vixDirection = v; }
    public String getScoreBreakdown() { return scoreBreakdown; }
    public void setScoreBreakdown(String v) { this.scoreBreakdown = v; }
    public Boolean getCommentaryDivergence() { return commentaryDivergence; }
    public void setCommentaryDivergence(Boolean v) { this.commentaryDivergence = v; }
    public String getKeyLevels() { return keyLevels; }
    public void setKeyLevels(String v) { this.keyLevels = v; }
    public String getRawInputs() { return rawInputs; }
    public void setRawInputs(String v) { this.rawInputs = v; }
    public String getDataGaps() { return dataGaps; }
    public void setDataGaps(String v) { this.dataGaps = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
