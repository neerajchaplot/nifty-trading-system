package com.the3Cgrp.zupptrade.agent2.config;

import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * Application-wide ObjectMapper.
     *
     * Jackson 3.x (tools.jackson) natively supports java.time types including LocalDate —
     * no separate JavaTimeModule required.
     *
     * LocalDate serialises as "2025-05-31" (ISO string) by default in Jackson 3.x,
     * which is correct for JSONB columns in trades, market_context etc.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
