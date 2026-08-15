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
import java.util.UUID;

/**
 * Populates {@link UserContext} for each request from one of two trusted sources:
 * <ul>
 *   <li><b>{@code Authorization: Bearer &lt;jwt&gt;}</b> — an end-user token (UI → agent), trusted
 *       after signature validation.</li>
 *   <li><b>{@code X-User-Id}</b> — the acting user's profile id forwarded on a trusted agent→agent
 *       call (behind the {@code X-API-Key} boundary), when no JWT is present.</li>
 * </ul>
 * Never blocks a request: an invalid token or malformed id simply leaves the request anonymous.
 * Clears the ThreadLocal in a finally block so identity never leaks across pooled threads.
 *
 * <p>Registered by {@link IdentityAutoConfiguration} only in modules WITHOUT Spring Security
 * (agents 1–5). agent-user has its own security-integrated filter.
 */
public class UserIdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserIdentityFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final UserContext userContext;

    public UserIdentityFilter(JwtService jwt, UserContext userContext) {
        this.jwt = jwt;
        this.userContext = userContext;
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
            String forwarded = request.getHeader(TradingConstants.USER_ID_HEADER);
            if (forwarded != null && !forwarded.isBlank()) {
                userContext.set(new AuthenticatedUser(UUID.fromString(forwarded.trim()), null, false, null));
            }
        } catch (JwtException | IllegalArgumentException | IllegalStateException e) {
            // Invalid/expired token, malformed id, or unconfigured key → anonymous. Do not block.
            log.debug("identity.resolve.skip: {}", e.getMessage());
        }
    }
}
