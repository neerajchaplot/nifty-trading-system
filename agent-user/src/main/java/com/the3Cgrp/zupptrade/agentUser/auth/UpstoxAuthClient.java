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

/**
 * Upstox OAuth login (identity + trading token). The token-exchange response carries the Upstox
 * {@code user_id} and {@code email} alongside the access token, so no extra profile call is needed.
 *
 * <p>client-id/secret come from UpstoxProperties (upstox.api.api-key/api-secret); the login
 * redirect URI comes from AuthProperties (the agent-user callback).
 */
@Component
public class UpstoxAuthClient {

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

        TokenExchangeResponse r = http.post()
                .uri("/v2/login/authorization/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenExchangeResponse.class);

        if (r == null || r.accessToken() == null || r.userId() == null) {
            throw new IllegalStateException("Upstox token exchange returned no access_token/user_id");
        }
        return new UpstoxIdentity(r.userId(), r.email(), r.accessToken());
    }

    public record UpstoxIdentity(String userId, String email, String accessToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenExchangeResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("user_id")      String userId,
            @JsonProperty("email")        String email
    ) {}
}
