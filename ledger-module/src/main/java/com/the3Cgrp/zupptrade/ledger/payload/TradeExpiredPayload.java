package com.the3Cgrp.zupptrade.ledger.payload;

import java.math.BigDecimal;

/**
 * Recorded when the morning expiry sweep detects an ACTIVE trade whose expiry_date
 * has passed. Agent 3 fetches the Nifty closing price on expiry_date and computes
 * final P&L from intrinsic values of each leg at settlement.
 */
public record TradeExpiredPayload(
        BigDecimal niftyCloseAtExpiry,
        BigDecimal computedPnl,
        String computedBy   // AGENT3:EXPIRY_SWEEP
) {}
