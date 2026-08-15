package com.the3Cgrp.zupptrade.agentUser.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google OIDC login (identity only — no broker token). Authorization-code flow:
 * build auth URL → exchange code for an id_token → verify it via Google's tokeninfo endpoint.
 *
 * <p>tokeninfo is used instead of local JWKS verification to stay dependency-light for v1;
 * the audience is checked against our client id.
 */
@Component
public class GoogleOidcClient {

    private static final String AUTH_URL  = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com";
    private static final String INFO_URL  = "https://oauth2.googleapis.com";

    private final AuthProperties props;
    private final RestClient http = RestClient.builder()
            .defaultHeader("Accept", "application/json").build();

    public GoogleOidcClient(AuthProperties props) {
        this.props = props;
    }

    public String buildAuthorizationUrl(String state) {
        // encode() percent-encodes query values (e.g. the spaces in scope) so the URL is valid.
        return UriComponentsBuilder.fromUriString(AUTH_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", props.getGoogle().getClientId())
                .queryParam("redirect_uri", props.getGoogle().getRedirectUri())
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .build().encode().toUriString();
    }

    /** Exchanges the code for an id_token and verifies it. Returns the verified identity. */
    public GoogleIdentity exchangeAndVerify(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", props.getGoogle().getClientId());
        form.add("client_secret", props.getGoogle().getClientSecret());
        form.add("redirect_uri", props.getGoogle().getRedirectUri());
        form.add("grant_type", "authorization_code");

        TokenResponse token = http.post()
                .uri(TOKEN_URL + "/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (token == null || token.idToken() == null) {
            throw new IllegalStateException("Google token exchange returned no id_token");
        }

        TokenInfo info = http.get()
                .uri(UriComponentsBuilder.fromUriString(INFO_URL + "/tokeninfo")
                        .queryParam("id_token", token.idToken()).build().toUriString())
                .retrieve()
                .body(TokenInfo.class);

        if (info == null || info.sub() == null) {
            throw new IllegalStateException("Google tokeninfo returned no subject");
        }
        if (!props.getGoogle().getClientId().equals(info.aud())) {
            throw new IllegalStateException("Google id_token audience mismatch");
        }
        return new GoogleIdentity(info.sub(), info.email(), info.name());
    }

    public record GoogleIdentity(String sub, String email, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("id_token") String idToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenInfo(String sub, String email, String name, String aud) {}
}
