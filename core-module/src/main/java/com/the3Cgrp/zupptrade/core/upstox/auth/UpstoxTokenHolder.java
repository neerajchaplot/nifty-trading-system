package com.the3Cgrp.zupptrade.core.upstox.auth;

import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxProperties;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the Upstox Bearer access token.
 *
 * On startup: initialised from upstox.api.access-token in application-local.yml.
 * Later: updateToken() will be called by UpstoxOAuthService once OAuth automation is wired.
 *
 * The upstoxRestClient reads from this holder on every request, so a token update
 * is immediately reflected in all subsequent API calls — no bean restart needed.
 */
public class UpstoxTokenHolder {

    private final AtomicReference<String> token = new AtomicReference<>();

    public UpstoxTokenHolder(UpstoxProperties props) {
        token.set(props.getAccessToken());
    }

    public String getToken() {
        return token.get();
    }

    /** Called by UpstoxOAuthService after a successful token exchange. */
    public void updateToken(String newToken) {
        token.set(newToken);
    }
}
