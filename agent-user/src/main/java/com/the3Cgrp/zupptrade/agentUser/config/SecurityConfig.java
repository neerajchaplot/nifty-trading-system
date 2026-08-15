package com.the3Cgrp.zupptrade.agentUser.config;

import com.the3Cgrp.zupptrade.agentUser.auth.JwtAuthFilter;
import com.the3Cgrp.zupptrade.core.security.JwtService;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security. Login/callback/refresh are public; everything else requires a valid
 * Bearer token (populated by the JWT filter). Unauthenticated requests get a plain 401 (not a
 * login-form redirect) so the SPA's interceptor can bounce the user to /login.
 *
 * <p>Note: agent-user now REQUIRES {@code JWT_SIGNING_KEY} to be usable — login mints tokens and
 * protected endpoints validate them. The other agents (1–5) remain on X-API-Key and are unaffected.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwt, UserContext userContext)
            throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/agent-user/auth/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(
                (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
            .addFilterBefore(new JwtAuthFilter(jwt, userContext), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
