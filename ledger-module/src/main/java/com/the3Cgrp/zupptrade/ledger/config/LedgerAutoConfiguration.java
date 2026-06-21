package com.the3Cgrp.zupptrade.ledger.config;

import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configures TradeLedgerService in any Spring Boot module that has a JdbcTemplate.
 * Activated via META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"
})
public class LedgerAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    public TradeLedgerService tradeLedgerService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new TradeLedgerService(jdbc, objectMapper);
    }
}
