package com.the3Cgrp.zupptrade.core.expiry;

import com.the3Cgrp.zupptrade.core.upstox.config.UpstoxAutoConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Auto-configures expiry date fetching + caching.
 *
 * No @ConditionalOnBean here — that annotation evaluates at bean-definition registration time,
 * before JdbcTemplateAutoConfiguration has created the JdbcTemplate bean, causing a false
 * negative even when a DataSource IS configured (same trap documented in UpstoxAutoConfiguration).
 *
 * @AutoConfigureAfter(UpstoxAutoConfiguration.class) guarantees upstoxRestClient is present.
 * JdbcTemplate is always available in agent contexts that have spring-boot-starter-data-jpa/jdbc.
 */
@AutoConfiguration
@AutoConfigureAfter(UpstoxAutoConfiguration.class)
public class ExpiryDateAutoConfiguration {

    @Bean
    public UpstoxExpiryClient upstoxExpiryClient(@Qualifier("upstoxRestClient") RestClient upstoxRestClient) {
        return new UpstoxExpiryClient(upstoxRestClient);
    }

    @Bean
    public ExpiryDateService expiryDateService(JdbcTemplate jdbc,
                                               UpstoxExpiryClient upstoxExpiryClient,
                                               ObjectMapper objectMapper) {
        return new ExpiryDateService(jdbc, upstoxExpiryClient, objectMapper);
    }
}
