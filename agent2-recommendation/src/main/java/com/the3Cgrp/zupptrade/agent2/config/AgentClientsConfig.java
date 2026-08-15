package com.the3Cgrp.zupptrade.agent2.config;

import com.the3Cgrp.zupptrade.core.security.ForwardUserIdInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Agent 2 → Agent 1 RestClient. Used by the FUTURES flow to (re)generate the Agent 1 signal from
 * the admin-submitted daily commentary before building a plan. URL is not a secret — configured in
 * application.yml (agent1.url), overridable via docker-compose / application-local.yml.
 */
@Configuration
public class AgentClientsConfig {

    @Bean("agent1RestClient")
    public RestClient agent1RestClient(@Value("${agent1.url:http://localhost:8081}") String agent1BaseUrl,
                                       ForwardUserIdInterceptor forwardUserId) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // Agent 1 scoring can take ~15s (Upstox + Marketaux + LLM + DB writes).
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(agent1BaseUrl)
                // Forwards X-User-Id when this recommend/futures flow runs on a UI-originated thread.
                .requestInterceptor(forwardUserId)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
