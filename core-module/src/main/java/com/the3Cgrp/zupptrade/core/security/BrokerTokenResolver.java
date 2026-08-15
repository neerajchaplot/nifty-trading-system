package com.the3Cgrp.zupptrade.core.security;

import com.the3Cgrp.zupptrade.core.upstox.crypto.TokenEncryptionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Resolves the decrypted Upstox token to act with, from {@code api_tokens} (keyed by user_profile_id).
 *
 * <ul>
 *   <li>{@code LIVE} user → their own token (orders on their account).</li>
 *   <li>{@code SIMULATION} user → the admin token (shared reads).</li>
 *   <li>{@link #resolveTokenForProfile(UUID)} → a specific profile's own token — used by the order
 *       path with the <b>trade owner</b> (scheduled flows where there's no request user).</li>
 * </ul>
 * Missing/blank token → {@link BrokerUnavailableException} (503). Agent-agnostic (JdbcTemplate).
 */
public class BrokerTokenResolver {

    private static final String SELECT_TOKEN =
            "SELECT encrypted_token FROM api_tokens WHERE service = 'UPSTOX' AND user_profile_id = ?";
    private static final String ADMIN_ID =
            "SELECT id FROM user_profiles WHERE is_admin = true ORDER BY created_at ASC LIMIT 1";

    private final JdbcTemplate jdbc;
    private final ObjectProvider<TokenEncryptionService> encryption;

    public BrokerTokenResolver(JdbcTemplate jdbc, ObjectProvider<TokenEncryptionService> encryption) {
        this.jdbc = jdbc;
        this.encryption = encryption;
    }

    /** The token the given authenticated user should act with (LIVE → own, SIMULATION → admin). */
    public String resolveUpstoxToken(AuthenticatedUser user) {
        if (user == null) {
            throw new BrokerUnavailableException("No authenticated user");
        }
        UUID owner = "LIVE".equals(user.accountMode()) ? user.profileId() : adminProfileId();
        return decrypt(readToken(owner), user.accountMode());
    }

    /** A specific profile's OWN Upstox token — for the order path (trade owner). */
    public String resolveTokenForProfile(UUID profileId) {
        if (profileId == null) {
            throw new BrokerUnavailableException("No trade owner");
        }
        return decrypt(readToken(profileId), "LIVE");
    }

    private UUID adminProfileId() {
        try {
            return jdbc.queryForObject(ADMIN_ID, UUID.class);
        } catch (EmptyResultDataAccessException e) {
            throw new BrokerUnavailableException("No admin user configured for shared market data");
        }
    }

    private String readToken(UUID profileId) {
        try {
            return jdbc.queryForObject(SELECT_TOKEN, String.class, profileId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private String decrypt(String encrypted, String mode) {
        if (encrypted == null || encrypted.isBlank()) {
            throw new BrokerUnavailableException("SIMULATION".equals(mode)
                    ? "Live data unavailable — the admin Upstox session has not been refreshed"
                    : "Upstox session not connected — please sign in to Upstox again");
        }
        TokenEncryptionService enc = encryption.getIfAvailable();
        if (enc == null) {
            throw new BrokerUnavailableException("Token decryption unavailable (TOKEN_ENCRYPTION_KEY unset)");
        }
        return enc.decrypt(encrypted);
    }
}
