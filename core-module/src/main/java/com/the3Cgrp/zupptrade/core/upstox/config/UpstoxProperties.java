package com.the3Cgrp.zupptrade.core.upstox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * All Upstox connectivity config — bound from upstox.api.*.
 * API key can be committed. API secret and access-token must be in application-local.yml (gitignored).
 */
@ConfigurationProperties(prefix = "upstox.api")
public class UpstoxProperties {

    private String baseUrl = "https://api.upstox.com";

    /**
     * Separate base URL for order placement, modify, and cancel.
     * Production : https://api-hft.upstox.com  (Upstox low-latency order gateway)
     * Sandbox    : https://api-sandbox.upstox.com
     * Set in application.yml per profile — this is NOT a secret.
     */
    private String orderBaseUrl = "https://api-hft.upstox.com";

    private String apiKey;
    private String apiSecret;
    private String accessToken;

    /**
     * Optional separate access token for the order RestClient.
     * Used in sandbox testing where orders go to api-sandbox.upstox.com (sandbox token)
     * but market data / margin goes to api.upstox.com (production token).
     * In production: leave blank — orderRestClient falls back to accessToken.
     * Env: UPSTOX_SANDBOX_TOKEN (sandbox only)
     */
    private String orderAccessToken;

    private String redirectUri = "http://localhost:8080/callback";
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 10;

    /**
     * AES-256 key for decrypting tokens written by the upstox-auth module.
     * Must match TOKEN_ENCRYPTION_KEY used by upstox-auth.
     * Set via: upstox.api.token-encryption-key=${TOKEN_ENCRYPTION_KEY}
     */
    private String tokenEncryptionKey;

    /** Token lifecycle settings — auto-fetch and persist on dev machines. */
    private Token token = new Token();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }

    public String getOrderBaseUrl() { return orderBaseUrl; }
    public void setOrderBaseUrl(String v) { this.orderBaseUrl = v; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }

    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String v) { this.apiSecret = v; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String v) { this.accessToken = v; }

    public String getOrderAccessToken() { return orderAccessToken; }
    public void setOrderAccessToken(String v) { this.orderAccessToken = v; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String v) { this.redirectUri = v; }

    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int v) { this.connectTimeoutSeconds = v; }

    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int v) { this.readTimeoutSeconds = v; }

    public String getTokenEncryptionKey()         { return tokenEncryptionKey; }
    public void   setTokenEncryptionKey(String v) { this.tokenEncryptionKey = v; }

    public Token getToken() { return token; }
    public void setToken(Token v) { this.token = v; }

    /**
     * Nested: upstox.api.token.*
     *
     * auto-fetch=true   — triggers browser OAuth on startup if token is missing (dev only)
     * persist-paths     — yml files to update with the new token after fetch
     */
    public static class Token {
        private boolean autoFetch = false;
        private List<String> persistPaths = List.of();

        public boolean isAutoFetch() { return autoFetch; }
        public void setAutoFetch(boolean v) { this.autoFetch = v; }

        public List<String> getPersistPaths() { return persistPaths; }
        public void setPersistPaths(List<String> v) { this.persistPaths = v; }
    }
}
