package com.the3Cgrp.zupptrade.agent5.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Input to POST /api/v1/agent5/execute.
 *
 * Sent by the orchestrator after Agent 2 /confirm returns CONFIRMED.
 * Agent 5 reads lots, lotSize, expectedNetPremium, and expiryDate from the
 * trades table using tradeId — only the execution-critical leg data is passed here.
 *
 * legs ordering convention:
 *   index 0 = short leg (SELL) — placed first for credit spreads
 *   index 1 = long leg  (BUY)  — placed simultaneously via multi/place
 * For debit spreads both legs go in the same multi/place call regardless of index.
 */
public record ExecuteTradeRequest(

        @NotNull
        UUID tradeId,

        @NotNull
        @NotEmpty
        @Valid
        List<LegOrderRequest> legs

) {}
