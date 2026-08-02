package com.the3Cgrp.zupptrade.agent3.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/**
 * Agent 3 → Agent 5 end-of-day close. Calls {@code POST /api/v1/agent5/futures/close/{planId}};
 * Agent 5 resolves realized P&L from the broker orders under the plan's tag and books CLOSED.
 * Mirrors {@link Agent5ExitClient} — Agent 3 schedules, Agent 5 touches the broker.
 */
@Component
public class Agent5FuturesCloseClient {

    private static final Logger log = LoggerFactory.getLogger(Agent5FuturesCloseClient.class);

    private final RestClient agent5RestClient;

    public Agent5FuturesCloseClient(@Qualifier("agent5RestClient") RestClient agent5RestClient) {
        this.agent5RestClient = agent5RestClient;
    }

    /** @return the close result status (CLOSED / UNRESOLVED / …), or empty if the call failed. */
    public Optional<String> close(UUID planId) {
        try {
            CloseResult res = agent5RestClient.post()
                    .uri("/api/v1/agent5/futures/close/{planId}", planId)
                    .retrieve()
                    .body(CloseResult.class);
            String status = res != null ? res.status() : null;
            log.info("agent3.futures.close.result planId={} status={}", planId, status);
            return Optional.ofNullable(status);
        } catch (Exception e) {
            log.warn("agent3.futures.close.failed planId={} error={}", planId, e.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CloseResult(String status) {}
}
