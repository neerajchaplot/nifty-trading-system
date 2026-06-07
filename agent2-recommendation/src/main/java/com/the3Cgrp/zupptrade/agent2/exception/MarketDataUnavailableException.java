package com.the3Cgrp.zupptrade.agent2.exception;

public class MarketDataUnavailableException extends RuntimeException {

    public MarketDataUnavailableException(String message) {
        super(message);
    }

    public MarketDataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
