package com.the3Cgrp.zupptrade.agent4.exception;

import java.util.UUID;

public class TradeNotFoundException extends RuntimeException {
    public TradeNotFoundException(UUID tradeId) {
        super("No closed trade found with id: " + tradeId);
    }
}
