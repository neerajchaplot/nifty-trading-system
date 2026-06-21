package com.the3Cgrp.zupptrade.agent3.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Agent 3 → Agent 1 and Agent 3 → Agent 2 RestClient configuration.
 *
 * Used exclusively by ReadjustmentService — Agent 3 only calls these agents
 * during automated readjustment after a T2 breach.
 *
 * URLs are NOT secrets: configured in application.yml.
 * Override in application-local.yml for dev or via docker-compose for containers.
 */
@Configuration
public class AgentClientsConfig {

    @Bean("agent1RestClient")
    public RestClient agent1RestClient(
            @Value("${agent1.url:http://localhost:8081}") String agent1BaseUrl) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // Agent 1 scoring can take ~15s (Upstox + Marketaux + DB writes)
        factory.setReadTimeout(Duration.ofSeconds(30));

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(agent1BaseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept",       MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean("agent2RestClient")
    public RestClient agent2RestClient(
            @Value("${agent2.url:http://localhost:8082}") String agent2BaseUrl) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // Agent 2 recommend includes Upstox option chain fetch (~10s)
        factory.setReadTimeout(Duration.ofSeconds(30));

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(agent2BaseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept",       MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
