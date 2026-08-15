package com.the3Cgrp.zupptrade.agent2.client;

import com.the3Cgrp.zupptrade.shared.dto.Agent1SignalDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies Agent 2 → Agent 1 /score carries the mandatory commentary AND the futures-specific
 * tier weighting (price 30, technical 20, institutional flow 20, volatility 10, commentary 20).
 */
class Agent1ScoreClientTest {

    private MockRestServiceServer server;
    private Agent1ScoreClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new Agent1ScoreClient(builder.build());
    }

    @Test
    void score_sendsCommentaryAndFuturesWeights_parsesSignal() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(endsWith("/api/v1/agent1/score")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.commentary").value("market looks firm"))
              .andExpect(jsonPath("$.fetchMarketaux").value(true))
              .andExpect(jsonPath("$.weights.tier1a").value(0.30))
              .andExpect(jsonPath("$.weights.tier1b").value(0.20))
              .andExpect(jsonPath("$.weights.tier2").value(0.20))
              .andExpect(jsonPath("$.weights.tier3").value(0.10))
              .andExpect(jsonPath("$.weights.tier4").value(0.20))
              .andExpect(jsonPath("$.source").value("FUTURES"))
              .andRespond(withSuccess(
                      "{\"id\":\"" + id + "\",\"bias\":\"BULLISH\",\"confidence\":\"HIGH\",\"confidenceScore\":0.80}",
                      MediaType.APPLICATION_JSON));

        Agent1SignalDto signal = client.score("market looks firm");

        assertThat(signal.id()).isEqualTo(id);
        assertThat(signal.bias().name()).isEqualTo("BULLISH");
        server.verify();
    }
}
