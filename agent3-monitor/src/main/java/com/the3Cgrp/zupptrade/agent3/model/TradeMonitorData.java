package com.the3Cgrp.zupptrade.agent3.model;

import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Minimal trade data read from the trades table via JdbcTemplate.
 * Agent 3 does not own the trades table and has no JPA entity for it.
 */
public record TradeMonitorData(
        UUID tradeId,
        UUID userProfileId,
        TradeStatus status,
        String monitorConfigJson,
        String marketContextJson,
        String tradeCode,
        LocalDate expiryDate,
        String entryFillsJson   // trades.entry_fills — written by Agent5; used to seed monitor_config
) {}
