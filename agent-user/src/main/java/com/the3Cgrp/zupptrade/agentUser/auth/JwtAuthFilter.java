package com.the3Cgrp.zupptrade.agentUser.auth;

import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.JwtService;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Bearer access token, and when valid populates {@link UserContext} and the Spring
 * SecurityContext. Invalid/absent tokens are ignored (routes are permitAll for now — enforcement
 * flips on in Phase 7 once the UI sends tokens), so this never blocks a request by itself.
 *
 * <p>Not a {@code @Component} on purpose — it is constructed by {@code SecurityConfig} and added to
 * the security chain only, avoiding a duplicate servlet-container registration whose
 * once-per-request guard could skip the in-chain copy.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwt;
    private final UserContext userContext;

    public JwtAuthFilter(JwtService jwt, UserContext userContext) {
        this.jwt = jwt;
        this.userContext = userContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthenticatedUser user = jwt.parseAccess(header.substring(7));
                userContext.set(user);

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                if (user.admin()) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));

                var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                // Invalid/expired token → treat as anonymous; do not fail the request here.
                log.debug("jwt.invalid: {}", e.getMessage());
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // UserContext is a ThreadLocal now — clear it so identity never leaks across pooled threads.
            userContext.clear();
        }
    }
}
