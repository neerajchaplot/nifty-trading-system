package com.the3Cgrp.zupptrade.agent3.client;

import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class Agent2RecommendClientTest {

    private Agent2RecommendClient client;
    private MockRestServiceServer mockServer;

    private static final UUID USER_PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SIGNAL_ID       = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TRADE_ID        = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent2-test");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new Agent2RecommendClient(builder.build());
    }

    // ── /recommend ────────────────────────────────────────────────────────────

    @Test
    void recommend_success_returnsTradeCard() {
        mockServer.expect(requestTo("http://agent2-test/api/v1/agent2/recommend"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.userProfileId").value(USER_PROFILE_ID.toString()))
                .andExpect(jsonPath("$.agent1SignalId").value(SIGNAL_ID.toString()))
                .andExpect(jsonPath("$.relaxedGate1PopPct").value(65.0))
                .andRespond(withSuccess(tradeCardJson(TRADE_ID, "PENDING_CONFIRM"), MediaType.APPLICATION_JSON));

        Optional<TradeCardDto> result = client.recommend(USER_PROFILE_ID, SIGNAL_ID, new BigDecimal("65.0"));

        assertThat(result).isPresent();
        assertThat(result.get().tradeId()).isEqualTo(TRADE_ID);
        assertThat(result.get().status()).isEqualTo(TradeStatus.PENDING_CONFIRM);
        mockServer.verify();
    }

    @Test
    void recommend_422GatesFailed_returnsEmpty() {
        // 422 = all gates passed structurally but no valid strikes found — not a server error
        mockServer.expect(requestTo("http://agent2-test/api/v1/agent2/recommend"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("{\"detail\":\"G1 PoP gate failed — no eligible strikes\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        Optional<TradeCardDto> result = client.recommend(USER_PROFILE_ID, SIGNAL_ID, new BigDecimal("65.0"));

        assertThat(result).isEmpty();
    }

    @Test
    void recommend_httpError500_returnsEmpty() {
        mockServer.expect(requestTo("http://agent2-test/api/v1/agent2/recommend"))
                .andRespond(withServerError());

        Optional<TradeCardDto> result = client.recommend(USER_PROFILE_ID, SIGNAL_ID, new BigDecimal("65.0"));

        assertThat(result).isEmpty();
    }

    @Test
    void recommend_relaxedPopSentCorrectly_stressedVix70() {
        // VIX > 22: stressed gate is 70%
        mockServer.expect(requestTo("http://agent2-test/api/v1/agent2/recommend"))
                .andExpect(jsonPath("$.relaxedGate1PopPct").value(70.0))
                .andRespond(withSuccess(tradeCardJson(TRADE_ID, "PENDING_CONFIRM"), MediaType.APPLICATION_JSON));

        Optional<TradeCardDto> result = client.recommend(USER_PROFILE_ID, SIGNAL_ID, new BigDecimal("70.0"));

        assertThat(result).isPresent();
    }

    // ── /confirm ──────────────────────────────────────────────────────────────

    @Test
    void confirm_success_returnsConfirmedCard() {
        mockServer.expect(requestTo("http://agent2-test/api/v1/agent2/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.tradeId").value(TRADE_ID.toString()))
                .andExpect(jsonPath("$.action").value("CONFIRM"))
                .andExpect(jsonPath("$.overrideLots").doesNotExist())
                .andRespond(withSuccess(tradeCardJson(TRADE_ID, "CONFIRMED"), MediaType.APPLICATION_JSON));

        Optional<TradeCardDto> result = client.confirm(TRADE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(TradeStatus.CONFIRMED);
        mockServer.verify();
    }

    @Test
    void confirm_httpError500_returnsEmpty() {
        mockServer.expect(requestTo("http://agent2-test/api/v1/agent2/confirm"))
                .andRespond(withServerError());

        Optional<TradeCardDto> result = client.confirm(TRADE_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void confirm_http404TradeNotFound_returnsEmpty() {
        mockServer.expect(requestTo("http://agent2-test/api/v1/agent2/confirm"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<TradeCardDto> result = client.confirm(TRADE_ID);

        assertThat(result).isEmpty();
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private String tradeCardJson(UUID tradeId, String status) {
        return """
                {
                  "tradeId": "%s",
                  "strategy": "BULL_PUT_SPREAD",
                  "spreadDirection": "CREDIT",
                  "expiryDate": "2026-06-17",
                  "dte": 3,
                  "shortLeg": {
                    "optionType": "PE",
                    "strike": 23500,
                    "ltp": "45.50",
                    "action": "SELL",
                    "delta": "-0.18",
                    "pop": "0.82",
                    "instrumentKey": "NFO_OPT|NIFTY|2026-06-17|23500|PE"
                  },
                  "longLeg": {
                    "optionType": "PE",
                    "strike": 23400,
                    "ltp": "28.20",
                    "action": "BUY",
                    "delta": "-0.14",
                    "pop": "0.88",
                    "instrumentKey": "NFO_OPT|NIFTY|2026-06-17|23400|PE"
                  },
                  "netPremiumPerUnit": "17.30",
                  "lots": 6,
                  "lotSize": 65,
                  "pop": "82.00",
                  "roc": "1.07",
                  "status": "%s"
                }
                """.formatted(tradeId, status);
    }
}
