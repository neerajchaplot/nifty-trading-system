package com.the3Cgrp.zupptrade.core.security;

import com.the3Cgrp.zupptrade.shared.constants.TradingConstants;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Populates {@link UserContext} for each request from one of two trusted sources:
 * <ul>
 *   <li><b>{@code Authorization: Bearer &lt;jwt&gt;}</b> — an end-user token (UI/mobile → agent),
 *       trusted after signature validation.</li>
 *   <li><b>{@code X-User-Id}</b> — the acting user's profile id forwarded on an agent→agent call,
 *       trusted <b>only when a valid {@code X-API-Key} accompanies it</b>. Without a valid key the
 *       header is ignored, so an external client cannot inject an identity (the edge also strips it).</li>
 * </ul>
 * Never blocks a request: an invalid token, wrong/missing key, or malformed id simply leaves the
 * request anonymous (per-endpoint {@code OwnershipGuard} decides 401/403). Clears the ThreadLocal in
 * a finally block so identity never leaks across pooled threads.
 *
 * <p>Registered by {@link IdentityAutoConfiguration} only in modules WITHOUT Spring Security
 * (agents 1–5). agent-user has its own security-integrated filter.
 */
public class UserIdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserIdentityFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final UserContext userContext;
    private final String internalApiKey;

    public UserIdentityFilter(JwtService jwt, UserContext userContext, String internalApiKey) {
        this.jwt = jwt;
        this.userContext = userContext;
        this.internalApiKey = internalApiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            resolve(request);
            chain.doFilter(request, response);
        } finally {
            userContext.clear();
        }
    }

    private void resolve(HttpServletRequest request) {
        try {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith(BEARER)) {
                userContext.set(jwt.parseAccess(auth.substring(BEARER.length())));
                return;
            }
            // Forwarded identity is trusted ONLY behind a valid internal API key.
            String forwarded = request.getHeader(TradingConstants.USER_ID_HEADER);
            if (forwarded != null && !forwarded.isBlank() && hasValidApiKey(request)) {
                userContext.set(new AuthenticatedUser(UUID.fromString(forwarded.trim()), null, false, null));
            }
        } catch (JwtException | IllegalArgumentException | IllegalStateException e) {
            // Invalid/expired token, malformed id, or unconfigured key → anonymous. Do not block.
            log.debug("identity.resolve.skip: {}", e.getMessage());
        }
    }

    /** Constant-time compare of the request's X-API-Key against the configured internal key. */
    private boolean hasValidApiKey(HttpServletRequest request) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            return false; // no key configured → never trust a forwarded identity
        }
        String provided = request.getHeader(TradingConstants.API_KEY_HEADER);
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                internalApiKey.getBytes(StandardCharsets.UTF_8));
    }
}
