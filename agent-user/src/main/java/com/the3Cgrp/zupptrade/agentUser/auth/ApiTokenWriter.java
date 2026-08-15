package com.the3Cgrp.zupptrade.agentUser.auth;

import com.the3Cgrp.zupptrade.core.upstox.crypto.TokenEncryptionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stores a user's broker token (encrypted) in api_tokens on login. First per-user writer in the
 * system. While api_tokens still has UNIQUE(service) (until Phase 4), this upserts the single
 * UPSTOX row and stamps its owning profile — correct for the single live/admin user today.
 *
 * TokenEncryptionService is looked up lazily so agent-user still boots if TOKEN_ENCRYPTION_KEY
 * is unset (the write then fails loudly only when a live login is attempted).
 */
@Component
public class ApiTokenWriter {

    // Phase 4: per-user token — one row per (user_profile_id, service). Upserts on that pair.
    private static final String UPSERT = """
            INSERT INTO api_tokens (service, encrypted_token, fetched_at, user_profile_id)
            VALUES ('UPSTOX', ?, NOW(), ?)
            ON CONFLICT (user_profile_id, service) DO UPDATE
              SET encrypted_token = EXCLUDED.encrypted_token,
                  fetched_at      = NOW(),
                  updated_at      = NOW()
            """;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<TokenEncryptionService> encryption;

    public ApiTokenWriter(JdbcTemplate jdbc, ObjectProvider<TokenEncryptionService> encryption) {
        this.jdbc = jdbc;
        this.encryption = encryption;
    }

    public void storeUpstoxToken(UUID userProfileId, String accessToken) {
        TokenEncryptionService enc = encryption.getIfAvailable();
        if (enc == null) {
            throw new IllegalStateException(
                    "Token encryption unavailable — set TOKEN_ENCRYPTION_KEY to store broker tokens");
        }
        jdbc.update(UPSERT, enc.encrypt(accessToken), userProfileId);
    }
}
