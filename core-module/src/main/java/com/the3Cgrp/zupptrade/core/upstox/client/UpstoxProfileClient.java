package com.the3Cgrp.zupptrade.core.upstox.client;

import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxUserProfile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * GET /v2/user/profile — simplest auth check.
 * Use this first to verify your access token is valid before calling market data endpoints.
 */
public class UpstoxProfileClient {

    private final RestClient upstoxRestClient;

    public UpstoxProfileClient(RestClient upstoxRestClient) {
        this.upstoxRestClient = upstoxRestClient;
    }

    public UpstoxUserProfile getProfile() {
        UpstoxApiResponse<UpstoxUserProfile> response = upstoxRestClient.get()
                .uri("/v2/user/profile")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (response == null || !response.isSuccess()) {
            throw new IllegalStateException("Upstox profile call failed: " + response);
        }
        return response.data();
    }
}
