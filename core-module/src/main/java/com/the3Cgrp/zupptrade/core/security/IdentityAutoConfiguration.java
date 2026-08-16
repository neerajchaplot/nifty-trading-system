package com.the3Cgrp.zupptrade.core.security;

import com.the3Cgrp.zupptrade.core.upstox.crypto.TokenEncryptionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared identity beans for every agent (and agent-user): the JWT service and the ThreadLocal
 * {@link UserContext}, plus the {@link UserIdentityFilter} that populates the context from a Bearer
 * JWT or a forwarded {@code X-User-Id}.
 *
 * <p>The filter is registered ONLY where Spring Security is absent (agents 1–5). agent-user has
 * Spring Security and its own security-integrated JwtAuthFilter, so the standalone filter is skipped
 * there to avoid double-processing. Servlet-web only.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(JwtProperties.class)
public class IdentityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtService jwtService(JwtProperties props) {
        return new JwtService(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public UserContext userContext() {
        return new UserContext();
    }

    /** Per-user read isolation (Phase 5): admin-bypass, 401-anonymous, 403-cross-user. */
    @Bean
    @ConditionalOnMissingBean
    public OwnershipGuard ownershipGuard(UserContext userContext) {
        return new OwnershipGuard(userContext);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnMissingClass("org.springframework.security.web.SecurityFilterChain")
    public UserIdentityFilter userIdentityFilter(JwtService jwt, UserContext userContext,
                                                 @Value("${X_API_KEY:}") String internalApiKey) {
        return new UserIdentityFilter(jwt, userContext, internalApiKey);
    }

    /** Attach to internal RestClient builders to forward the acting user on agent→agent calls. */
    @Bean
    @ConditionalOnMissingBean
    public ForwardUserIdInterceptor forwardUserIdInterceptor(UserContext userContext,
                                                             @Value("${X_API_KEY:}") String internalApiKey) {
        return new ForwardUserIdInterceptor(userContext, internalApiKey);
    }

    /** Resolves a user's decrypted Upstox token (order path). Needs a JdbcTemplate (all agents have one). */
    @Bean
    @ConditionalOnMissingBean
    public BrokerTokenResolver brokerTokenResolver(JdbcTemplate jdbc,
                                                   ObjectProvider<TokenEncryptionService> encryption) {
        return new BrokerTokenResolver(jdbc, encryption);
    }
}

