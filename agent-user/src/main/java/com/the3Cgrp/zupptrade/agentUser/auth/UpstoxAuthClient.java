package com.the3Cgrp.zupptrade.agentUser.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upstox OAuth login (identity + trading token). The token-exchange response carries the Upstox
 * {@code user_id} and {@code email} alongside the access token, so no extra profile call is needed.
 *
 * <p>client-id/secret come from UpstoxProperties (upstox.api.api-key/api-secret); the login
 * redirect URI comes from AuthProperties (the agent-user callback).
 */
@Component
public class UpstoxAuthClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxAuthClient.class);

    private final UpstoxProperties upstox;
    private final AuthProperties auth;
    private final RestClient http;

    public UpstoxAuthClient(UpstoxProperties upstox, AuthProperties auth) {
        this.upstox = upstox;
        this.auth = auth;
        this.http = RestClient.builder()
                .baseUrl(upstox.getBaseUrl())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(upstox.getBaseUrl())
                .path("/v2/login/authorization/dialog")
                .queryParam("response_type", "code")
                .queryParam("client_id", upstox.getApiKey())
                .queryParam("redirect_uri", auth.getUpstox().getRedirectUri())
                .queryParam("state", state)
                .build().encode().toUriString();
    }

    public UpstoxIdentity exchange(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", upstox.getApiKey());
        form.add("client_secret", upstox.getApiSecret());
        form.add("redirect_uri", auth.getUpstox().getRedirectUri());
        form.add("grant_type", "authorization_code");

        // TEMP DIAGNOSTIC: capture the RAW token-exchange response to inspect the token TYPE Upstox
        // returns (presence of extended_token, user_type, scopes/products). Token values are masked.
        // Remove after Upstox order-API diagnosis is closed.
        String raw = http.post()
                .uri("/v2/login/authorization/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);

        log.warn("upstox.token.exchange.raw hasExtendedToken={} response={}",
                raw != null && raw.contains("extended_token"), maskTokens(raw));

        String accessToken = extractJsonString(raw, "access_token");
        String userId      = extractJsonString(raw, "user_id");
        String email       = extractJsonString(raw, "email");
        if (accessToken == null || userId == null) {
            throw new IllegalStateException("Upstox token exchange returned no access_token/user_id");
        }
        return new UpstoxIdentity(userId, email, accessToken);
    }

    // ── TEMP DIAGNOSTIC helpers (remove with the log above) ──────────────────────
    /** Minimal JSON string-field extractor (no Jackson dependency) — matches Upstox's compact JSON. */
    private static String extractJsonString(String json, String key) {
        if (json == null) return null;
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    /** Replaces access_token / extended_token VALUES with a length+ends fingerprint so the response
     *  structure (field names, user_type, products, scopes) is logged without leaking the token. */
    private static String maskTokens(String json) {
        if (json == null) return null;
        for (String k : new String[]{"access_token", "extended_token"}) {
            String v = extractJsonString(json, k);
            if (v != null && v.length() > 8) {
                String fp = "len=" + v.length() + "," + v.substring(0, 4) + "…" + v.substring(v.length() - 4);
                json = json.replace(v, "<" + fp + ">");
            }
        }
        return json;
    }

    public record UpstoxIdentity(String userId, String email, String accessToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenExchangeResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("user_id")      String userId,
            @JsonProperty("email")        String email
    ) {}
}
