package com.the3Cgrp.zupptrade.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints and validates the app's own JWT session tokens (HS256). agent-user mints them at login;
 * every agent validates them. Registered as a bean by {@link IdentityAutoConfiguration}.
 *
 * <p>Two token types share the signing key: {@code access} (short-lived, carries mode/admin) and
 * {@code refresh} (long-lived, subject only). {@code typ} is asserted on parse so a refresh token
 * can never be presented as an access token.
 *
 * <p>Boot-tolerant: if no valid signing key is configured it starts anyway (so agents boot before
 * auth is configured) and fails loudly only when mint/validate is actually attempted.
 */
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_TYPE     = "typ";
    private static final String CLAIM_MODE     = "mode";
    private static final String CLAIM_ADMIN    = "admin";
    private static final String CLAIM_PROVIDER = "provider";
    private static final String TYPE_ACCESS    = "access";
    private static final String TYPE_REFRESH   = "refresh";

    private final SecretKey key; // null when no valid signing key is configured
    private final JwtProperties props;

    public JwtService(JwtProperties props) {
        this.props = props;
        boolean valid = props.getSigningKey() != null
                && props.getSigningKey().getBytes(StandardCharsets.UTF_8).length >= 32;
        this.key = valid ? Keys.hmacShaKeyFor(props.getSigningKey().getBytes(StandardCharsets.UTF_8)) : null;
        if (!valid) {
            log.warn("JWT not configured — set JWT_SIGNING_KEY (>= 32 bytes). Auth will fail until set.");
        }
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new IllegalStateException("JWT signing key not configured (set JWT_SIGNING_KEY, >= 32 bytes)");
        }
        return key;
    }

    public TokenPair issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        Instant accessExp = now.plusSeconds(props.getAccessTtlMinutes() * 60);
        Instant refreshExp = now.plusSeconds(props.getRefreshTtlDays() * 24 * 3600);

        String access = Jwts.builder()
                .issuer(props.getIssuer())
                .subject(user.profileId().toString())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_MODE, user.accountMode())
                .claim(CLAIM_ADMIN, user.admin())
                .claim(CLAIM_PROVIDER, user.authProvider())
                .issuedAt(Date.from(now))
                .expiration(Date.from(accessExp))
                .signWith(requireKey())
                .compact();

        String refresh = Jwts.builder()
                .issuer(props.getIssuer())
                .subject(user.profileId().toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(refreshExp))
                .signWith(requireKey())
                .compact();

        return new TokenPair(access, refresh, props.getAccessTtlMinutes() * 60);
    }

    /** Parses + validates an access token. Throws {@link JwtException} if invalid, expired, or not an access token. */
    public AuthenticatedUser parseAccess(String token) {
        Claims c = parse(token);
        if (!TYPE_ACCESS.equals(c.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("Not an access token");
        }
        return new AuthenticatedUser(
                UUID.fromString(c.getSubject()),
                c.get(CLAIM_MODE, String.class),
                Boolean.TRUE.equals(c.get(CLAIM_ADMIN, Boolean.class)),
                c.get(CLAIM_PROVIDER, String.class));
    }

    /** Parses + validates a refresh token, returning the subject profile id. */
    public UUID parseRefreshSubject(String token) {
        Claims c = parse(token);
        if (!TYPE_REFRESH.equals(c.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("Not a refresh token");
        }
        return UUID.fromString(c.getSubject());
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(requireKey()).build().parseSignedClaims(token).getPayload();
    }
}
