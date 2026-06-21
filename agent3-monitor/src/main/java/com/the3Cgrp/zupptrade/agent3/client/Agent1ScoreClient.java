package com.the3Cgrp.zupptrade.agent3.client;

import com.the3Cgrp.zupptrade.shared.dto.Agent1SignalDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 3 → Agent 1 REST client.
 *
 * Used exclusively by ReadjustmentService to fetch a fresh market signal
 * before recommending a re-entry trade. Commentary is omitted (null) in the
 * automated readjust flow — Agent 1 scores all tiers deterministically from
 * market data and Marketaux; no user input is needed.
 *
 * Returns Optional.empty() on any failure — callers must handle absent signal gracefully.
 */
@Service
public class Agent1ScoreClient {

    private static final Logger log = LoggerFactory.getLogger(Agent1ScoreClient.class);

    private final RestClient agent1RestClient;

    public Agent1ScoreClient(@Qualifier("agent1RestClient") RestClient agent1RestClient) {
        this.agent1RestClient = agent1RestClient;
    }

    /**
     * Calls Agent 1 /score to produce a fresh signal for the given expiry.
     * fetchMarketaux=true: always fetch live news sentiment in the readjust path.
     *
     * @return the new signal ID, or empty on any failure
     */
    public Optional<UUID> score(LocalDate expiryDate) {
        ScoreRequest request = new ScoreRequest(expiryDate, null, true);

        try {
            Agent1SignalDto signal = agent1RestClient.post()
                    .uri("/api/v1/agent1/score")
                    .body(request)
                    .retrieve()
                    .body(Agent1SignalDto.class);

            if (signal == null || signal.id() == null) {
                log.error("agent1.score.null_response expiryDate={}", expiryDate);
                return Optional.empty();
            }

            log.info("agent1.score.success signalId={} bias={} strength={} expiryDate={}",
                    signal.id(), signal.bias(), signal.strength(), expiryDate);
            return Optional.of(signal.id());

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                log.error("agent1.score.vix_extreme expiryDate={} body={}", expiryDate, e.getResponseBodyAsString());
            } else {
                log.error("agent1.score.http_error expiryDate={} httpStatus={} body={}",
                        expiryDate, e.getStatusCode(), e.getResponseBodyAsString());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("agent1.score.error expiryDate={} error={}", expiryDate, e.getMessage());
            return Optional.empty();
        }
    }

    /** Local projection — matches ScoreRequestDto JSON shape in agent1-market_analyst. */
    private record ScoreRequest(LocalDate expiryDate, String commentary, boolean fetchMarketaux) {}
}
