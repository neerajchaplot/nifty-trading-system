package com.the3Cgrp.zupptrade.core.upstox.config;

import com.the3Cgrp.zupptrade.core.upstox.auth.ApiTokenDbLoader;
import com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxOAuthService;
import com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenFetcher;
import com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenHolder;
import com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenStartupRunner;
import com.the3Cgrp.zupptrade.core.upstox.crypto.TokenEncryptionService;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxMarketQuoteClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxOptionChainClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxPositionClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxProfileClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Spring Boot auto-configuration for Upstox connectivity.
 * Activated automatically when core-module is on the classpath — no @Import needed.
 *
 * Provides:
 *   - UpstoxTokenHolder   — thread-safe token storage (read from config, updatable at runtime)
 *   - UpstoxOAuthService  — builds auth URL + exchanges auth code for access token
 *   - upstoxRestClient    — pre-configured RestClient with dynamic Bearer token interceptor
 *   - UpstoxProfileClient, UpstoxHistoricalDataClient, UpstoxMarketQuoteClient, UpstoxOptionChainClient
 */
@AutoConfiguration
// Ensures JdbcTemplate is auto-configured before this class is processed,
// so @ConditionalOnClass(JdbcTemplate.class) + constructor injection both work correctly.
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration")
@EnableConfigurationProperties(UpstoxProperties.class)
public class UpstoxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UpstoxAutoConfiguration.class);

    @Bean
    public UpstoxTokenHolder upstoxTokenHolder(UpstoxProperties props) {
        return new UpstoxTokenHolder(props);
    }

    @Bean
    public UpstoxOAuthService upstoxOAuthService(UpstoxProperties props, UpstoxTokenHolder tokenHolder) {
        return new UpstoxOAuthService(props, tokenHolder);
    }

    /**
     * The upstoxRestClient reads the Bearer token from UpstoxTokenHolder on every request
     * via a request interceptor — so a token update (daily paste or OAuth refresh) is
     * immediately reflected without restarting the app.
     */
    @Bean("upstoxRestClient")
    public RestClient upstoxRestClient(UpstoxProperties props, UpstoxTokenHolder tokenHolder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.getReadTimeoutSeconds()));

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.getBaseUrl())
                .requestInterceptor((request, body, execution) -> {
                    String token = tokenHolder.getToken();
                    if (token != null && !token.isBlank()) {
                        request.getHeaders().setBearerAuth(token);
                    }
                    request.getHeaders().set("Accept", "application/json");
                    request.getHeaders().set("Api-Version", "2.0");
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * Dedicated RestClient for Upstox order operations (place, modify, cancel).
     *
     * Production: points to api-hft.upstox.com — Upstox's low-latency order gateway,
     *   separate from the standard API host. Uses the same production access token.
     *
     * Sandbox: points to api-sandbox.upstox.com. Uses orderAccessToken if configured
     *   (the 30-day sandbox token), otherwise falls back to accessToken.
     *   This allows sandbox tests to route orders to the sandbox while margin checks
     *   still hit the real api.upstox.com with the production token for accurate data.
     *
     * Inject this bean — NOT upstoxRestClient — in any class that places, modifies,
     * or cancels orders.
     */
    @Bean("upstoxOrderRestClient")
    public RestClient upstoxOrderRestClient(UpstoxProperties props, UpstoxTokenHolder tokenHolder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.getReadTimeoutSeconds()));

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.getOrderBaseUrl())
                .requestInterceptor((request, body, execution) -> {
                    // Use orderAccessToken when set (sandbox); fall back to accessToken (production)
                    String orderToken = props.getOrderAccessToken();
                    String token = (orderToken != null && !orderToken.isBlank())
                            ? orderToken
                            : tokenHolder.getToken();
                    if (token != null && !token.isBlank()) {
                        request.getHeaders().setBearerAuth(token);
                    }
                    request.getHeaders().set("Accept", "application/json");
                    request.getHeaders().set("Api-Version", "2.0");
                    return execution.execute(request, body);
                })
                .build();
    }

    @Bean
    public UpstoxProfileClient upstoxProfileClient(RestClient upstoxRestClient) {
        return new UpstoxProfileClient(upstoxRestClient);
    }

    @Bean
    public UpstoxHistoricalDataClient upstoxHistoricalDataClient(RestClient upstoxRestClient) {
        return new UpstoxHistoricalDataClient(upstoxRestClient);
    }

    @Bean
    public UpstoxMarketQuoteClient upstoxMarketQuoteClient(RestClient upstoxRestClient) {
        return new UpstoxMarketQuoteClient(upstoxRestClient);
    }

    @Bean
    public UpstoxOptionChainClient upstoxOptionChainClient(RestClient upstoxRestClient) {
        return new UpstoxOptionChainClient(upstoxRestClient);
    }

    @Bean
    public UpstoxPositionClient upstoxPositionClient(RestClient upstoxRestClient) {
        return new UpstoxPositionClient(upstoxRestClient);
    }

    /**
     * Loads the Upstox access token from the api_tokens table on startup.
     * Only active when upstox.api.token-encryption-key is configured.
     *
     * Uses ObjectProvider<JdbcTemplate> for lazy resolution — evaluated at run() time
     * (after full context initialisation), NOT at auto-configuration registration time.
     *
     * Why ObjectProvider instead of @ConditionalOnBean(JdbcTemplate.class):
     *   @ConditionalOnBean is evaluated during auto-configuration registration, which happens
     *   before JdbcTemplateAutoConfiguration has finished creating its beans — causing a
     *   false negative that silently skips the loader even when a DataSource IS configured.
     *   ObjectProvider.getIfAvailable() resolves at run() time when all beans are ready.
     *
     * Spring Boot 4 NullBean note:
     *   Returning null from a @Bean method registers a NullBean, which crashes
     *   callRunners() with BeanNotOfRequiredTypeException. This is safe here because
     *   when JdbcTemplate IS available (DataSource + driver on classpath), getIfAvailable()
     *   returns a real JdbcTemplate and we never return null. The null branch only fires
     *   when no DataSource is configured — in which case the bean is skipped gracefully.
     *   If no token source is available at all, UpstoxTokenHolder stays empty and
     *   the first API call will log upstox.token.expired.
     *
     *   IMPORTANT: if adding a new context where DataSource IS expected but JdbcTemplate
     *   is still null (e.g., test-only context without JDBC auto-config), fix it by adding
     *   spring-boot-starter-jdbc to that module's test scope — not by changing this bean.
     */
    @Bean
    @ConditionalOnProperty(name = "upstox.api.token-encryption-key")
    @ConditionalOnClass(JdbcTemplate.class)
    public ApiTokenDbLoader apiTokenDbLoader(ObjectProvider<JdbcTemplate> jdbcProvider,
                                             UpstoxProperties props,
                                             UpstoxTokenHolder tokenHolder) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            log.warn("upstox.db.token.loader.skipped — JdbcTemplate bean not available (no datasource configured)");
            return null;
        }
        return new ApiTokenDbLoader(jdbc, new TokenEncryptionService(props.getTokenEncryptionKey()), tokenHolder);
    }

    @Bean
    public UpstoxTokenFetcher upstoxTokenFetcher(UpstoxProperties props) {
        return new UpstoxTokenFetcher(props);
    }

    /**
     * Only active when upstox.token.auto-fetch=true.
     * Add this to application-local.yml on developer machines.
     * In CI/production, supply the token via environment variable instead.
     */
    @Bean
    @ConditionalOnProperty(name = "upstox.api.token.auto-fetch", havingValue = "true")
    public UpstoxTokenStartupRunner upstoxTokenStartupRunner(
            UpstoxTokenHolder tokenHolder,
            UpstoxTokenFetcher tokenFetcher,
            UpstoxProfileClient profileClient,
            UpstoxProperties props) {
        return new UpstoxTokenStartupRunner(
                tokenHolder, tokenFetcher, profileClient, props.getToken().getPersistPaths());
    }
}
