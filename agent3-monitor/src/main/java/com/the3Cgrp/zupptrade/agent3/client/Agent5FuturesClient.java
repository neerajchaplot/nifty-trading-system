package com.the3Cgrp.zupptrade.agent3.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 3 → Agent 5 handoff for a confirmed futures entry.
 * Calls {@code POST /api/v1/agent5/futures/gtt} with the plan id; Agent 5 reads the plan's
 * entry/stop/target/lots from trade_future_ledger and places the multi-leg GTT (OCO), advancing
 * the plan to FILLED (or to EXECUTION_FAILED, with its own critical alert, if it rejects).
 *
 * This method only reports whether Agent 5 was REACHED. If it was not (transport error / Agent 5
 * down), the caller fails the plan fast and raises a critical alert — no retry.
 */
@Component
public class Agent5FuturesClient {

    private static final Logger log = LoggerFactory.getLogger(Agent5FuturesClient.class);

    private final RestClient agent5RestClient;

    public Agent5FuturesClient(@Qualifier("agent5RestClient") RestClient agent5RestClient) {
        this.agent5RestClient = agent5RestClient;
    }

    /**
     * @return true if Agent 5 was reached and processed the handoff (it owns the FILLED /
     *         EXECUTION_FAILED transition); false if Agent 5 was unreachable.
     */
    public boolean placeGtt(UUID planId) {
        try {
            FuturesGttResponse res = agent5RestClient.post()
                    .uri("/api/v1/agent5/futures/gtt")
                    .body(Map.of("planId", planId.toString()))
                    .retrieve()
                    .body(FuturesGttResponse.class);
            log.info("agent3.futures.handoff.ok planId={} status={} gttOrderId={}",
                    planId, res != null ? res.status() : null, res != null ? res.gttOrderId() : null);
            return true;
        } catch (Exception e) {
            log.warn("agent3.futures.handoff.unreachable planId={} error={}", planId, e.getMessage());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FuturesGttResponse(String gttOrderId, String status) {}
}
