package com.the3Cgrp.zupptrade.agent1.dto;

import java.time.LocalDate;

/**
 * Request body for POST /api/v1/agent1/score.
 * expiryDate: optional — auto-resolved from ExpiryDateService (Upstox) when absent.
 * commentary: optional — user-provided market commentary text for LLM extraction.
 * fetchMarketaux: defaults to true; set explicitly to false to skip Marketaux and save quota.
 */
public record ScoreRequestDto(
        LocalDate expiryDate,
        String commentary,
        Boolean fetchMarketaux
) {
    public boolean shouldFetchMarketaux() {
        return !Boolean.FALSE.equals(fetchMarketaux); // true unless explicitly set to false
    }
}
