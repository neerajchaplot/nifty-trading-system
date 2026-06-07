package com.the3Cgrp.zupptrade.core.upstox.auth;

import com.the3Cgrp.zupptrade.core.upstox.crypto.TokenEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * On startup: reads the AES-256-GCM encrypted Upstox token from the api_tokens
 * table (written by the upstox-auth module) and loads it into UpstoxTokenHolder.
 *
 * Runs at Order(1) — before UpstoxTokenStartupRunner (Order(2)) — so validation
 * and browser OAuth fallback in UpstoxTokenStartupRunner sees the DB token first.
 *
 * Only active when:
 *   - JdbcTemplate is on the classpath (i.e., spring-jdbc / JPA is a dependency)
 *   - upstox.api.token-encryption-key is configured
 *
 * If the api_tokens table doesn't exist yet (auth module not yet run), this
 * loader silently skips — the UpstoxTokenStartupRunner browser fallback takes over.
 */
@Order(1)
public class ApiTokenDbLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenDbLoader.class);

    private static final String SQL =
        "SELECT encrypted_token FROM api_tokens WHERE service = 'UPSTOX' " +
        "ORDER BY fetched_at DESC LIMIT 1";

    private final JdbcTemplate           jdbc;
    private final TokenEncryptionService encryption;
    private final UpstoxTokenHolder      tokenHolder;

    public ApiTokenDbLoader(JdbcTemplate jdbc,
                            TokenEncryptionService encryption,
                            UpstoxTokenHolder tokenHolder) {
        this.jdbc        = jdbc;
        this.encryption  = encryption;
        this.tokenHolder = tokenHolder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String encrypted = jdbc.queryForObject(SQL, String.class);
            if (encrypted == null || encrypted.isBlank()) {
                log.info("upstox.db.token.absent no row in api_tokens");
                return;
            }
            String token = encryption.decrypt(encrypted);
            tokenHolder.updateToken(token);
            log.info("upstox.db.token.loaded token_length={}", token.length());
        } catch (Exception e) {
            // Table doesn't exist yet, permission denied, or decryption failed — fall through to startup runner
            Throwable root = e.getCause() != null ? e.getCause() : e;
            log.warn("upstox.db.token.load.skipped reason={} rootCause={}", e.getMessage(), root.getMessage());
        }
    }
}
