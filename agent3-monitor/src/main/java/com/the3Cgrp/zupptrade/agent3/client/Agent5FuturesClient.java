package com.the3Cgrp.zupptrade.agent3.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 3 → Agent 5 handoff for a confirmed futures entry.
 * Calls {@code POST /api/v1/agent5/futures/gtt} with the plan id; Agent 5 reads the plan's
 * entry/stop/target/lots from trade_future_ledger and places the multi-leg GTT (OCO).
 *
 * NOTE: the Agent 5 endpoint is delivered in the next slice. Until then this call fails softly —
 * the plan is still marked CONFIRMED so screen 2 advances, and Agent 5 can pick up CONFIRMED
 * plans when it comes online. Agent 5 must be idempotent by trade tag to avoid a double GTT.
 */
@Component
public class Agent5FuturesClient {

    private static final Logger log = LoggerFactory.getLogger(Agent5FuturesClient.class);

    private final RestClient agent5RestClient;

    public Agent5FuturesClient(@Qualifier("agent5RestClient") RestClient agent5RestClient) {
        this.agent5RestClient = agent5RestClient;
    }

    /** @return the broker GTT order id if Agent 5 accepted, else empty (call failed / not yet built). */
    public Optional<String> placeGtt(UUID planId) {
        try {
            FuturesGttResponse res = agent5RestClient.post()
                    .uri("/api/v1/agent5/futures/gtt")
                    .body(Map.of("planId", planId.toString()))
                    .retrieve()
                    .body(FuturesGttResponse.class);
            if (res != null && res.gttOrderId() != null) {
                log.info("agent3.futures.handoff.ok planId={} gttOrderId={}", planId, res.gttOrderId());
                return Optional.of(res.gttOrderId());
            }
            log.warn("agent3.futures.handoff.empty planId={}", planId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("agent3.futures.handoff.failed planId={} error={} (Agent 5 futures endpoint pending)",
                    planId, e.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FuturesGttResponse(String gttOrderId, String status) {}
}
