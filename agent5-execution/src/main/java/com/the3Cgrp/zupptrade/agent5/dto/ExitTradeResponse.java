package com.the3Cgrp.zupptrade.agent5.dto;

import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response from POST /api/v1/agent5/exit/{tradeId}.
 *
 * status values after an exit attempt:
 *   CLOSED           — exit orders placed successfully; position is flat on Upstox.
 *   EXIT_IN_PROGRESS — guard response: another exit is already in progress for this trade.
 *   EXIT_FAILED      — orders could not be placed; position may still be open.
 *                      Agent 3 will retry on next cycle. failureReason explains what happened.
 */
public record ExitTradeResponse(
        UUID tradeId,
        TradeStatus status,
        String failureReason,   // null on success
        LocalDateTime closedAt  // null on failure
) {}
