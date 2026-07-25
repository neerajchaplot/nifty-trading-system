package com.the3Cgrp.zupptrade.agent1.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only view of the shared {@code user_profiles} table — Agent 1 only needs the five
 * tier weights to personalise the composite score. The write side (validation, defaults,
 * full column set) lives in the agent-user module; this entity intentionally maps a subset
 * so it stays a pure read. Column definitions mirror the canonical mapping in agent2.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", unique = true, nullable = false, length = 50)
    private String userId;

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

    public UUID getId()                { return id; }
    public String getUserId()          { return userId; }
    public BigDecimal getTier1aWeight() { return tier1aWeight; }
    public BigDecimal getTier1bWeight() { return tier1bWeight; }
    public BigDecimal getTier2Weight()  { return tier2Weight; }
    public BigDecimal getTier3Weight()  { return tier3Weight; }
    public BigDecimal getTier4Weight()  { return tier4Weight; }
}
