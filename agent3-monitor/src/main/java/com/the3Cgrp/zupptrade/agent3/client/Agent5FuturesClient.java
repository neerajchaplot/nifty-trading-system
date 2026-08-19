package com.the3Cgrp.zupptrade.agent3.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 3 → Agent 5 handoff for a confirmed futures entry.
 * Calls {@code POST /api/v1/agent5/futures/gtt} with the plan id; Agent 5 reads the plan's
 * entry/stop/target/lots from trade_future_ledger and places the multi-leg GTT (OCO), advancing
 * the plan to FILLED (or to EXECUTION_FAILED, with its own critical alert, if it rejects).
 *
 * The result tells the caller whether Agent 5 took ownership of the plan. A REACHED result means
 * Agent 5 processed the request and owns the FILLED / EXECUTION_FAILED transition (caller does
 * nothing). A FAILED result — whether Agent 5 was unreachable (transport error) OR reached but
 * returned an HTTP error (e.g. a precondition rejection, in which case it did NOT take ownership) —
 * means the caller fails the plan fast and raises a critical alert; no retry.
 */
@Component
public class Agent5FuturesClient {

    private static final Logger log = LoggerFactory.getLogger(Agent5FuturesClient.class);

    private final RestClient agent5RestClient;

    public Agent5FuturesClient(@Qualifier("agent5RestClient") RestClient agent5RestClient) {
        this.agent5RestClient = agent5RestClient;
    }

    public HandoffResult placeGtt(UUID planId) {
        try {
            FuturesGttResponse res = agent5RestClient.post()
                    .uri("/api/v1/agent5/futures/gtt")
                    .body(Map.of("planId", planId.toString()))
                    .retrieve()
                    .body(FuturesGttResponse.class);
            log.info("agent3.futures.handoff.ok planId={} status={} gttOrderId={}",
                    planId, res != null ? res.status() : null, res != null ? res.gttOrderId() : null);
            return HandoffResult.ok();
        } catch (RestClientResponseException e) {
            // Agent 5 WAS reached but answered with an HTTP error — it did not take ownership of the
            // plan, so the caller must fail it. Report the real reason, NOT "unreachable".
            log.warn("agent3.futures.handoff.rejected planId={} status={} body={}",
                    planId, e.getStatusCode(), e.getResponseBodyAsString());
            return HandoffResult.failed("Agent 5 rejected the handoff (HTTP " + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            // Transport failure — Agent 5 was genuinely unreachable (down / network / timeout).
            log.warn("agent3.futures.handoff.unreachable planId={} error={}", planId, e.getMessage());
            return HandoffResult.failed("Agent 5 was unreachable (" + e.getMessage() + ")");
        }
    }

    /**
     * Outcome of a handoff attempt. {@code reached} = Agent 5 processed the request and owns the
     * plan's FILLED / EXECUTION_FAILED transition. Otherwise {@code failureReason} carries a
     * user-facing explanation and the caller fails the plan fast (no retry).
     */
    public record HandoffResult(boolean reached, String failureReason) {
        public static HandoffResult ok() { return new HandoffResult(true, null); }
        public static HandoffResult failed(String reason) { return new HandoffResult(false, reason); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FuturesGttResponse(String gttOrderId, String status) {}
}
