package com.the3Cgrp.zupptrade.core.upstox.auth;

import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxProperties;
import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxTokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Upstox OAuth 2.0 Authorization Code flow.
 *
 * Step 1 — direct user to buildAuthorizationUrl() in a browser.
 * Step 2 — Upstox redirects to redirectUri?code=AUTH_CODE after login.
 * Step 3 — call exchangeCode(authCode) to get the Bearer access token.
 *
 * The resulting token is stored in UpstoxTokenHolder and used by upstoxRestClient
 * for all subsequent API calls.
 *
 * TODO: Wire Step 1/2 into a lightweight controller endpoint so the daily token
 *       refresh can be done via browser without manual copy-paste.
 */
public class UpstoxOAuthService {

    private static final Logger log = LoggerFactory.getLogger(UpstoxOAuthService.class);

    private final UpstoxProperties props;
    private final UpstoxTokenHolder tokenHolder;
    private final RestClient plainRestClient; // no Bearer header — used for token exchange

    public UpstoxOAuthService(UpstoxProperties props, UpstoxTokenHolder tokenHolder) {
        this.props = props;
        this.tokenHolder = tokenHolder;
        this.plainRestClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Returns the URL the user must visit in a browser to authorise the app.
     * Register http://localhost:8080/callback in the Upstox developer portal.
     */
    public String buildAuthorizationUrl() {
        return UriComponentsBuilder
                .fromUriString(props.getBaseUrl())
                .path("/v2/login/authorization/dialog")
                .queryParam("response_type", "code")
                .queryParam("client_id", props.getApiKey())
                .queryParam("redirect_uri", props.getRedirectUri())
                .build()
                .toUriString();
    }

    /**
     * Exchanges the authorization code (from the redirect callback) for an access token.
     * Stores the new token in UpstoxTokenHolder.
     *
     * @param authCode the `code` query parameter received at the redirect URI
     * @return the new access token
     */
    public String exchangeCode(String authCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authCode);
        form.add("client_id", props.getApiKey());
        form.add("client_secret", props.getApiSecret());
        form.add("redirect_uri", props.getRedirectUri());
        form.add("grant_type", "authorization_code");

        UpstoxTokenResponse response = plainRestClient.post()
                .uri("/v2/login/authorization/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(UpstoxTokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Upstox token exchange returned null access_token");
        }

        tokenHolder.updateToken(response.accessToken());
        log.info("upstox.token.refreshed", "New access token stored in UpstoxTokenHolder");
        return response.accessToken();
    }
}
