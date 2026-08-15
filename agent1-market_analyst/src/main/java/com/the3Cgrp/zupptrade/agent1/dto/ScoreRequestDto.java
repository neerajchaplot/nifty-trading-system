package com.the3Cgrp.zupptrade.agent1.dto;

import com.the3Cgrp.zupptrade.shared.enums.SignalSource;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for POST /api/v1/agent1/score.
 * expiryDate: optional — auto-resolved from ExpiryDateService (Upstox) when absent.
 * commentary: optional — user-provided market commentary text for LLM extraction.
 * fetchMarketaux: defaults to true; set explicitly to false to skip Marketaux and save quota.
 * weights: optional per-request tier-weight override (used by the FUTURES flow, which weights
 *   commentary and institutional flow differently). When absent, the profile/config weights apply.
 */
public record ScoreRequestDto(
        LocalDate expiryDate,
        String commentary,
        Boolean fetchMarketaux,
        TierWeights weights,
        SignalSource source
) {
    /** Backward-compatible 3-arg form (no weight override, TRADING source). */
    public ScoreRequestDto(LocalDate expiryDate, String commentary, Boolean fetchMarketaux) {
        this(expiryDate, commentary, fetchMarketaux, null, null);
    }

    /** 4-arg form (weights, TRADING source) — keeps existing callers compiling. */
    public ScoreRequestDto(LocalDate expiryDate, String commentary, Boolean fetchMarketaux, TierWeights weights) {
        this(expiryDate, commentary, fetchMarketaux, weights, null);
    }

    public boolean shouldFetchMarketaux() {
        return !Boolean.FALSE.equals(fetchMarketaux); // true unless explicitly set to false
    }

    /** Channel this run belongs to — defaults to TRADING when the caller doesn't specify. */
    public SignalSource effectiveSource() {
        return source != null ? source : SignalSource.TRADING;
    }

    /** Per-request tier weights (fractions summing to 1.0). All five must be present to apply. */
    public record TierWeights(
            BigDecimal tier1a, BigDecimal tier1b, BigDecimal tier2, BigDecimal tier3, BigDecimal tier4) {
        public boolean complete() {
            return tier1a != null && tier1b != null && tier2 != null && tier3 != null && tier4 != null;
        }
    }
}
