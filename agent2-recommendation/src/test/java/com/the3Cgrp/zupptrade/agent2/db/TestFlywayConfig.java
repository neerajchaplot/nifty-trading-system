package com.the3Cgrp.zupptrade.agent2.db;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Forces a clean + migrate on every test context load.
 * Requires spring.flyway.clean-disabled=false in the test profile.
 */
@TestConfiguration
public class TestFlywayConfig {

    @Bean
    public FlywayMigrationStrategy cleanMigrateStrategy() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
