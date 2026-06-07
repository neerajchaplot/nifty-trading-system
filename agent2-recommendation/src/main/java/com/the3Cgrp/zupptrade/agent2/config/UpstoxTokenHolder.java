package com.the3Cgrp.zupptrade.agent2.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the live Upstox Bearer token.
 *
 * Initialised on startup from application config (fallback).
 * Overwritten by UpstoxTokenDbLoader if a valid encrypted token exists in api_tokens table.
 *
 * RestClientConfig reads from this holder via a request interceptor on every call,
 * so a token update is immediately reflected without restarting the app or the RestClient bean.
 */
@Component
public class UpstoxTokenHolder {

    private final AtomicReference<String> token = new AtomicReference<>();

    public UpstoxTokenHolder(TradingConfig config) {
        // Seed from config — acts as fallback if DB token loading is skipped or fails
        token.set(config.getUpstox().getAccessToken());
    }

    public String getToken() {
        return token.get();
    }

    /** Called by UpstoxTokenDbLoader on startup once the DB token is decrypted. */
    public void updateToken(String freshToken) {
        token.set(freshToken);
    }
}
