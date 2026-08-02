package com.the3Cgrp.zupptrade.agent2.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.the3Cgrp.zupptrade.shared.dto.Agent1SignalDto;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Agent 2 → Agent 1 /score client. The futures flow calls this with the admin-submitted daily
 * commentary to (re)generate a fresh Agent 1 signal before building the plan — so the mandatory
 * commentary actually shapes the futures bias/confidence.
 */
@Component
public class Agent1ScoreClient {

    private static final Logger log = LoggerFactory.getLogger(Agent1ScoreClient.class);

    // Futures-specific tier weighting (spec): price 30%, technical 20%, institutional flow 20%,
    // volatility 10%, commentary 20% — boosts commentary + trims institutional flow vs Agent 1 defaults.
    private static final TierWeights FUTURES_WEIGHTS = new TierWeights(
            new BigDecimal("0.30"), new BigDecimal("0.20"), new BigDecimal("0.20"),
            new BigDecimal("0.10"), new BigDecimal("0.20"));

    private final RestClient agent1RestClient;

    public Agent1ScoreClient(@Qualifier("agent1RestClient") RestClient agent1RestClient) {
        this.agent1RestClient = agent1RestClient;
    }

    /**
     * Generates a fresh Agent 1 signal from the given commentary (expiry auto-resolved by Agent 1,
     * Marketaux fetched). Throws on failure — the futures recommend must not proceed without a signal.
     */
    public Agent1SignalDto score(String commentary) {
        try {
            // Deserialize into a minimal view: Agent1SignalDto's scoreBreakdown/keyLevels/dataGaps are
            // @JsonRawValue Strings (serialize-only) and arrive as JSON objects here — reading them
            // into a String fails. We only need id/bias/strength/confidence, so ignore the rest.
            ScoreResponse r = agent1RestClient.post()
                    .uri("/api/v1/agent1/score")
                    .body(new ScoreRequest(null, commentary, true, FUTURES_WEIGHTS))
                    .retrieve()
                    .body(ScoreResponse.class);
            if (r == null || r.id() == null) {
                throw new IllegalStateException("Agent 1 /score returned no signal");
            }
            log.info("agent2.agent1.score.ok", kv("signalId", r.id()),
                    kv("bias", r.bias()), kv("confidence", r.confidence()));
            return new Agent1SignalDto(r.id(), null, null, r.bias(), r.strength(), null,
                    r.confidenceScore(), r.confidence(), null, null, null, null, null, null, null, null, null);
        } catch (RuntimeException e) {
            log.error("agent2.agent1.score.failed error={}", e.getMessage(), e);
            throw new IllegalStateException("Agent 1 scoring failed: " + e.getMessage(), e);
        }
    }

    /**
     * Minimal view of the Agent 1 /score response — only the fields the futures flow uses.
     * ignoreUnknown skips scoreBreakdown/keyLevels/dataGaps (raw-JSON objects that can't map to String).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScoreResponse(UUID id, Bias bias, Strength strength,
                                 BigDecimal confidenceScore, Confidence confidence) {}

    /** Matches agent1 ScoreRequestDto JSON shape (expiryDate, commentary, fetchMarketaux, weights). */
    private record ScoreRequest(LocalDate expiryDate, String commentary, boolean fetchMarketaux,
                                TierWeights weights) {}

    /** Matches agent1 ScoreRequestDto.TierWeights JSON shape. */
    private record TierWeights(BigDecimal tier1a, BigDecimal tier1b, BigDecimal tier2,
                               BigDecimal tier3, BigDecimal tier4) {}
}
