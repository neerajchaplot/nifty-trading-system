package com.the3Cgrp.zupptrade.agent1.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for POST /api/v1/agent1/score.
 * commentary: optional — user-provided market commentary text for LLM extraction.
 * fetchMarketaux: if false, skip Marketaux API call (saves quota).
 */
public record ScoreRequestDto(
        @NotNull LocalDate expiryDate,
        String commentary,
        boolean fetchMarketaux
) {}
