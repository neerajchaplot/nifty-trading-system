package com.the3Cgrp.zupptrade.ledger.payload;

import java.util.UUID;

/**
 * Recorded when user confirms — status transitions CONFIRMED.
 */
public record TradeApprovedPayload(
        UUID userProfileId,
        Integer overrideLots   // null if user accepted Agent 2 sizing
) {}
