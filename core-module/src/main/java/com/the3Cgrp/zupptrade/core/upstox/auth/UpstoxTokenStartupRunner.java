package com.the3Cgrp.zupptrade.core.upstox.auth;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxProfileClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.file.Paths;
import java.util.List;

/**
 * On startup: validates the configured Upstox access token by calling the profile endpoint.
 * Triggers a browser OAuth flow if the token is absent, placeholder, or expired (401).
 * Persists the new token to yml files so subsequent restarts skip the browser entirely.
 *
 * Activated when upstox.api.token.auto-fetch=true (add to application-local.yml on dev machines).
 * In CI/production, supply the token via environment variable — this runner stays inactive.
 *
 * Configuration example (application-local.yml):
 * <pre>
 * upstox:
 *   api:
 *     token:
 *       auto-fetch: true
 *       persist-paths:
 *         - src/test/resources/application-local.yml
 * </pre>
 */
public class UpstoxTokenStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UpstoxTokenStartupRunner.class);
    private static final String PLACEHOLDER = "PASTE_FRESH_ACCESS_TOKEN_HERE";

    private final UpstoxTokenHolder  tokenHolder;
    private final UpstoxTokenFetcher tokenFetcher;
    private final UpstoxProfileClient profileClient;
    private final List<String>       persistPaths;

    public UpstoxTokenStartupRunner(UpstoxTokenHolder tokenHolder,
                                    UpstoxTokenFetcher tokenFetcher,
                                    UpstoxProfileClient profileClient,
                                    List<String> persistPaths) {
        this.tokenHolder   = tokenHolder;
        this.tokenFetcher  = tokenFetcher;
        this.profileClient = profileClient;
        this.persistPaths  = persistPaths != null ? persistPaths : List.of();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (isTokenUsable()) {
            log.info("upstox.token.valid skipping OAuth flow");
            return;
        }
        log.info("upstox.token.invalid starting browser OAuth flow");
        fetchAndStore();
    }

    /**
     * Returns true only if the current token passes a live API check.
     * A missing/placeholder token skips the network call entirely.
     * Any non-401 error (network down, timeout) is treated as "token OK" —
     * we only re-auth when Upstox explicitly rejects the token.
     */
    private boolean isTokenUsable() {
        String current = tokenHolder.getToken();
        if (current == null || current.isBlank() || PLACEHOLDER.equals(current)) {
            log.info("upstox.token.absent token is missing or placeholder");
            return false;
        }
        try {
            profileClient.getProfile();
            return true;
        } catch (HttpClientErrorException.Unauthorized e) {
            log.info("upstox.token.expired 401 received — token has expired");
            return false;
        } catch (Exception e) {
            // Network issue, timeout, etc. — don't force re-auth; let the app start
            log.warn("upstox.token.check.failed assuming valid: {}", e.getMessage());
            return true;
        }
    }

    private void fetchAndStore() throws Exception {
        String newToken = tokenFetcher.fetchToken();
        tokenHolder.updateToken(newToken);
        log.info("upstox.token.stored token_length={}", newToken.length());

        for (String path : persistPaths) {
            try {
                UpstoxTokenFetcher.updateAccessTokenInYml(Paths.get(path), newToken);
            } catch (Exception e) {
                log.warn("upstox.token.persist.failed path={} error={}", path, e.getMessage());
            }
        }
    }
}
