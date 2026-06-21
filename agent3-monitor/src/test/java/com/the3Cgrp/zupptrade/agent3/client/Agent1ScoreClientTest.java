package com.the3Cgrp.zupptrade.agent3.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class Agent1ScoreClientTest {

    private Agent1ScoreClient client;
    private MockRestServiceServer mockServer;

    private static final UUID SIGNAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 17);

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent1-test");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new Agent1ScoreClient(builder.build());
    }

    @Test
    void score_success_returnsSignalId() {
        mockServer.expect(requestTo("http://agent1-test/api/v1/agent1/score"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.expiryDate").value("2026-06-17"))
                .andExpect(jsonPath("$.fetchMarketaux").value(true))
                .andExpect(jsonPath("$.commentary").doesNotExist())
                .andRespond(withSuccess(agentSignalJson(SIGNAL_ID), MediaType.APPLICATION_JSON));

        Optional<UUID> result = client.score(EXPIRY);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(SIGNAL_ID);
        mockServer.verify();
    }

    @Test
    void score_httpError500_returnsEmpty() {
        mockServer.expect(requestTo("http://agent1-test/api/v1/agent1/score"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        Optional<UUID> result = client.score(EXPIRY);

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void score_http503_returnsEmpty() {
        // 503 = VIX Extreme — Agent 1 refuses to score
        mockServer.expect(requestTo("http://agent1-test/api/v1/agent1/score"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("{\"detail\":\"VIX is in Extreme territory (>24). Scoring blocked.\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        Optional<UUID> result = client.score(EXPIRY);

        assertThat(result).isEmpty();
    }

    @Test
    void score_emptyBody_returnsEmpty() {
        mockServer.expect(requestTo("http://agent1-test/api/v1/agent1/score"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        Optional<UUID> result = client.score(EXPIRY);

        assertThat(result).isEmpty();
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private String agentSignalJson(UUID id) {
        return """
                {
                  "id": "%s",
                  "timestamp": "2026-06-14T09:20:00",
                  "expiryDate": "2026-06-17",
                  "bias": "BEARISH",
                  "strength": "MILD",
                  "compositeScore": "-0.3200",
                  "confidenceScore": "0.60",
                  "confidence": "MEDIUM",
                  "vixLevel": "18.50",
                  "vixRegime": "NORMAL",
                  "vixDirection": "Falling"
                }
                """.formatted(id);
    }
}
