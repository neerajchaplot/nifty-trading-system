package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.dto.UpstoxStatusResponse;
import com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenHolder;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxProfileClient;
import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxProperties;
import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxUserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Verifies live Upstox API connectivity by calling GET /v2/user/profile.
 *
 * Used by GET /api/v1/agent5/upstox/status — call this endpoint before starting
 * a trading session to confirm the token is valid and Upstox is reachable.
 *
 * This call is read-only and never modifies state. Runs in ~200ms on a healthy connection.
 *
 * Sandbox note: this check validates the production token (margin check gateway).
 * The sandbox order gateway uses a separate token (UPSTOX_SANDBOX_TOKEN); its presence
 * is reported in sandboxTokenConfigured but not validated via a live call here.
 */
@Service
public class UpstoxConnectionCheckService {

    private static final Logger log = LoggerFactory.getLogger(UpstoxConnectionCheckService.class);

    private final UpstoxProfileClient profileClient;
    private final UpstoxTokenHolder   tokenHolder;
    private final UpstoxProperties    props;

    public UpstoxConnectionCheckService(UpstoxProfileClient profileClient,
                                        UpstoxTokenHolder tokenHolder,
                                        UpstoxProperties props) {
        this.profileClient = profileClient;
        this.tokenHolder   = tokenHolder;
        this.props         = props;
    }

    /** @return true if a non-blank token is currently in UpstoxTokenHolder. */
    public boolean isTokenLoaded() {
        String token = tokenHolder.getToken();
        return token != null && !token.isBlank();
    }

    /**
     * Performs a live connectivity check against the Upstox production API.
     * Always returns a non-null result — never throws.
     */
    public UpstoxStatusResponse check() {
        String gateway = props.getOrderBaseUrl();
        boolean sandboxTokenConfigured = props.getOrderAccessToken() != null
                && !props.getOrderAccessToken().isBlank();

        if (!isTokenLoaded()) {
            log.warn("upstox.connection.check token absent");
            return UpstoxStatusResponse.tokenAbsent(gateway);
        }

        try {
            UpstoxUserProfile profile = profileClient.getProfile();
            log.info("upstox.connection.check ok userId={} sandbox={}",
                    profile.userId(), sandboxTokenConfigured);
            return UpstoxStatusResponse.connected(
                    profile.userId(), profile.userName(), sandboxTokenConfigured, gateway);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("upstox.connection.check token expired (401)");
            return UpstoxStatusResponse.tokenExpired(gateway);
        } catch (Exception e) {
            log.warn("upstox.connection.check api unreachable error={}", e.getMessage());
            return UpstoxStatusResponse.unreachable(e.getMessage(), gateway);
        }
    }
}
