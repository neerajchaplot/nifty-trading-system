package com.the3Cgrp.zupptrade.agent2.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * On startup: reads the AES-256-GCM encrypted Upstox token from the api_tokens
 * table (written by the upstox-auth job) and loads it into UpstoxTokenHolder.
 *
 * Prerequisites:
 *   - TOKEN_ENCRYPTION_KEY env var must be set (same key used by upstox-auth)
 *   - trading.upstox.token-encryption-key=${TOKEN_ENCRYPTION_KEY} in application.yml
 *   - upstox-auth job must have run and written a token row to api_tokens
 *
 * Safe fallbacks:
 *   - No encryption key configured → skips silently (holder keeps config token)
 *   - api_tokens table missing → logs warning, skips
 *   - Decryption fails → logs warning, skips
 *
 * Runs at Order(1) so the token is live before any Upstox API calls are made.
 */
@Component
@Order(1)
public class UpstoxTokenDbLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UpstoxTokenDbLoader.class);

    private static final String SQL =
            "SELECT encrypted_token FROM api_tokens WHERE service = 'UPSTOX' " +
            "ORDER BY fetched_at DESC LIMIT 1";

    // AES-256-GCM parameters — must match upstox-auth's TokenEncryptionService
    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH  = 12;
    private static final int    TAG_LENGTH = 128;

    private final JdbcTemplate       jdbc;
    private final UpstoxTokenHolder  tokenHolder;
    private final TradingConfig      config;

    public UpstoxTokenDbLoader(JdbcTemplate jdbc,
                               UpstoxTokenHolder tokenHolder,
                               TradingConfig config) {
        this.jdbc        = jdbc;
        this.tokenHolder = tokenHolder;
        this.config      = config;
    }

    @Override
    public void run(ApplicationArguments args) {
        String encryptionKey = config.getUpstox().getTokenEncryptionKey();
        if (encryptionKey == null || encryptionKey.isBlank()) {
            log.info("upstox.db.token.skipped — TOKEN_ENCRYPTION_KEY not configured; " +
                     "using access-token from application config");
            return;
        }

        try {
            String encrypted = jdbc.queryForObject(SQL, String.class);
            if (encrypted == null || encrypted.isBlank()) {
                log.warn("upstox.db.token.absent — no row in api_tokens; " +
                         "run upstox-auth job first");
                return;
            }

            String token = decrypt(encryptionKey, encrypted);
            tokenHolder.updateToken(token);
            log.info("upstox.db.token.loaded token_length={}", token.length());

        } catch (Exception e) {
            // Table missing, decryption failed, or permission denied — fall back to config token
            Throwable root = e.getCause() != null ? e.getCause() : e;
            log.warn("upstox.db.token.load.failed reason='{}' rootCause='{}'; " +
                     "using access-token from application config",
                     e.getMessage(), root.getMessage());
        }
    }

    /**
     * AES-256-GCM decryption.
     * Encrypted format: Base64(IV[12] || Ciphertext+AuthTag)
     * Must match the encryption performed by the upstox-auth TokenEncryptionService.
     */
    private static String decrypt(String base64Key, String encryptedBase64) throws Exception {
        byte[] keyBytes  = Base64.getDecoder().decode(base64Key);
        byte[] combined  = Base64.getDecoder().decode(encryptedBase64);
        byte[] iv        = Arrays.copyOfRange(combined, 0, IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
