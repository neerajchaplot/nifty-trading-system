package com.the3Cgrp.zupptrade.core.alert;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configures AlertService when a JdbcTemplate is available.
 *
 * Active in all agents that have a DataSource (agent3, agent5, agent2, etc.).
 * Not active in shared-domain or contexts without a DB connection.
 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration")
public class AlertAutoConfiguration {

    @Bean
    @ConditionalOnClass(JdbcTemplate.class)
    public CriticalAlertService criticalAlertService(JdbcTemplate jdbc) {
        return new CriticalAlertService(jdbc);
    }

    @Bean
    @ConditionalOnClass(JdbcTemplate.class)
    public AlertService alertService(JdbcTemplate jdbc, CriticalAlertService criticalAlertService) {
        return new AlertService(jdbc, criticalAlertService);
    }
}
