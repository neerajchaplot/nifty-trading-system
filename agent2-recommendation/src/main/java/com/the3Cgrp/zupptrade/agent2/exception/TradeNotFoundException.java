package com.the3Cgrp.zupptrade.agent2.exception;

import java.util.UUID;

public class TradeNotFoundException extends RuntimeException {

    private final UUID tradeId;

    public TradeNotFoundException(UUID tradeId) {
        super("Trade not found: " + tradeId);
        this.tradeId = tradeId;
    }

    public UUID getTradeId() { return tradeId; }
}
