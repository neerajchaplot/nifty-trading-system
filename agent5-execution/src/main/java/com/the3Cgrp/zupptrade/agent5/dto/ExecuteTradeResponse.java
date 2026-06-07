package com.the3Cgrp.zupptrade.agent5.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response from POST /api/v1/agent5/execute.
 *
 * executionStatus:
 *   ACTIVE   — all legs filled, trade is live in the market
 *   REJECTED — margin insufficient, payload error, or exchange rejection; no position open
 *   FAILED   — unexpected error; check rejectionReason; assume no position
 *
 * slippageAlert:
 *   true when actualNetPremiumPerUnit < expectedNetPremiumPerUnit × (1 − threshold).
 *   Trade is still ACTIVE — this is a warning for the user, not an abort signal.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecuteTradeResponse(
        UUID tradeId,
        TradeStatus executionStatus,
        List<LegFillDto> fills,
        BigDecimal actualNetPremiumPerUnit,
        BigDecimal expectedNetPremiumPerUnit,
        boolean slippageAlert,
        String slippageMessage,
        String rejectionReason,
        LocalDateTime executedAt
) {}
