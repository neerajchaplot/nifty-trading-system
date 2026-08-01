package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A single user-actionable critical alert (critical_alerts table, V112).
 *
 * <p>These represent situations the system could NOT resolve automatically and that require the
 * end user to act — e.g. an ambiguous order-placement failure where a position may be open on the
 * exchange. {@code tradeDetails} is the transparent JSONB snapshot of trade state as the system
 * knew it, surfaced as a nested object (Map) so the UI can render it directly.
 */
public record CriticalAlertDto(

        UUID alertId,
        UUID tradeId,
        String alertReason,
        Map<String, Object> tradeDetails,
        String status,
        LocalDateTime createdAt,
        LocalDateTime acknowledgedAt
) {}
