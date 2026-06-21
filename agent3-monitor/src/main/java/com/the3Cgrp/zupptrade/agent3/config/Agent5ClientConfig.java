package com.the3Cgrp.zupptrade.agent3.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Agent 3 → Agent 5 RestClient configuration.
 *
 * The base URL is in application.yml (not an env var — URLs are not secrets).
 * In docker-compose, set agent5.url to http://agent5-execution:8085.
 * In local dev (two JVMs), set it to http://localhost:8085.
 */
@Configuration
public class Agent5ClientConfig {

    @Bean("agent5RestClient")
    public RestClient agent5RestClient(
            @Value("${agent5.url:http://localhost:8085}") String agent5BaseUrl) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // Exit can take up to ~60s (multi/place + exchange routing)
        factory.setReadTimeout(Duration.ofSeconds(90));

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(agent5BaseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
