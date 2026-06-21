package com.the3Cgrp.zupptrade.ledger.payload;

import com.the3Cgrp.zupptrade.shared.enums.Strategy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Recorded when Agent 2 generates a trade card — status transitions PENDING_CONFIRM.
 */
public record TradePendingPayload(
        UUID agent1SignalId,
        UUID userProfileId,
        Strategy strategy,
        BigDecimal compositeScore,
        String bias,
        String strength,
        BigDecimal netPremiumPerUnit,
        int lots,
        int lotSize,
        BigDecimal maxProfit,
        BigDecimal maxLossTheoretical,
        BigDecimal pop,
        BigDecimal rocPct
) {}
