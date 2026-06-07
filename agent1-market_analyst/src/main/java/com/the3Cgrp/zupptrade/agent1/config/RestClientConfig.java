package com.the3Cgrp.zupptrade.agent1.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Agent1-specific REST clients.
 * upstoxRestClient is provided by core-module's UpstoxAutoConfiguration — not defined here.
 */
@Configuration
public class RestClientConfig {

    @Bean("marketauxRestClient")
    public RestClient marketauxRestClient(
            @Value("${marketaux.api.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Bean("nseRestClient")
    public RestClient nseRestClient() {
        return RestClient.builder()
                .baseUrl("https://www.nseindia.com")
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Referer", "https://www.nseindia.com")
                .build();
    }

    /**
     * Groq API client — OpenAI-compatible endpoint, Bearer token auth.
     * Free tier: 14,400 req/day, 30 req/min — no credit card required.
     * API key injected from GROQ_API_KEY environment variable — never hardcoded.
     */
    @Bean("groqRestClient")
    public RestClient groqRestClient(
            @Value("${groq.api.base-url}") String baseUrl,
            @Value("${groq.api.key}") String apiKey) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
