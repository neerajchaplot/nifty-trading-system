package com.the3Cgrp.zupptrade.agent5.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of an end-of-day futures close.
 * status = CLOSED (realized P&L booked) | UNRESOLVED (round-trip not found under tag — alerted).
 */
public record FuturesCloseResponse(
        UUID planId,
        BigDecimal realizedPnl,
        String closeReason,
        String status,
        String message
) {}
