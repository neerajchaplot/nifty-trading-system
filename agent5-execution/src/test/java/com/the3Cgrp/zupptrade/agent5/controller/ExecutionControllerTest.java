package com.the3Cgrp.zupptrade.agent5.controller;

import com.the3Cgrp.zupptrade.agent5.dto.*;
import com.the3Cgrp.zupptrade.shared.dto.ExitTradeRequest;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller integration tests for Agent 5 REST endpoints.
 *
 * Pattern: same as agent2's RecommendationE2ETest —
 * full Spring context on RANDOM_PORT, real HTTP via RestClient, no mocks.
 *
 * Coverage:
 *   GET  /health                          → 200 UP (no DB, no Upstox needed)
 *   POST /execute with missing tradeId    → 400 (Bean Validation before service)
 *   POST /execute with empty legs         → 400 (Bean Validation)
 *   POST /execute with limitPrice = 0     → 400 (Bean Validation)
 *   POST /execute with unknown tradeId    → 200 REJECTED (DB returns null → service rejects early)
 *   POST /exit/{tradeId} mismatch body    → 400 (controller rejects before service call)
 *   POST /exit with missing reason        → 400 (Bean Validation)
 *
 * The valid /execute flow end-to-end (ACTIVE) is covered by TradeExecutionSandboxIT T5.
 *
 * Run:
 *   mvn test -pl agent5-execution "-Dexcluded.test.groups=" -Dgroups=integration
 *
 * Prerequisites: same as all integration tests — TOKEN_ENCRYPTION_KEY set,
 * valid token in Neon DB api_tokens, Neon DB reachable via application-local.yml.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionControllerTest {

    @Value("${local.server.port}")
    private int port;

    // A tradeId that is guaranteed NOT to be in the DB (random UUID each run)
    private static final UUID UNKNOWN_TRADE_ID = UUID.randomUUID();

    // A mismatched UUID for path vs body tests
    private static final UUID PATH_TRADE_ID    = UUID.randomUUID();
    private static final UUID BODY_TRADE_ID    = UUID.randomUUID();

    // ── Health ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /health → 200 status=UP")
    void health_returns200WithStatusUp() {
        ResponseEntity<String> response = restClient()
                .get()
                .uri("/api/v1/agent5/health")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    // ── POST /execute — Bean Validation (400) ────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("POST /execute missing tradeId → 400")
    void execute_missingTradeId_returns400() {
        String body = """
                {
                    "legs": [{
                        "instrumentKey": "NFO_OPT|NIFTY|2026-06-09|24500|PE",
                        "optionType": "PE", "strike": 24500,
                        "action": "SELL", "limitPrice": "50.00", "quantity": 75
                    }]
                }
                """;
        assertBadRequest("/api/v1/agent5/execute", body, "missing tradeId");
    }

    @Test
    @Order(3)
    @DisplayName("POST /execute empty legs array → 400")
    void execute_emptyLegs_returns400() {
        String body = """
                { "tradeId": "%s", "legs": [] }
                """.formatted(UNKNOWN_TRADE_ID);
        assertBadRequest("/api/v1/agent5/execute", body, "empty legs");
    }

    @Test
    @Order(4)
    @DisplayName("POST /execute limitPrice = 0 → 400 (must be ≥ 0.05)")
    void execute_zeroPriceLeg_returns400() {
        String body = """
                {
                    "tradeId": "%s",
                    "legs": [{
                        "instrumentKey": "NFO_OPT|NIFTY|2026-06-09|24500|PE",
                        "optionType": "PE", "strike": 24500,
                        "action": "SELL", "limitPrice": "0.00", "quantity": 75
                    }]
                }
                """.formatted(UNKNOWN_TRADE_ID);
        assertBadRequest("/api/v1/agent5/execute", body, "zero limitPrice");
    }

    // ── POST /execute — Service-level rejection (200 REJECTED) ───────────────

    @Test
    @Order(5)
    @DisplayName("POST /execute unknown tradeId → 200 REJECTED (trade not found in DB)")
    void execute_unknownTradeId_returns200Rejected() {
        ExecuteTradeRequest request = new ExecuteTradeRequest(UNKNOWN_TRADE_ID, List.of(
                new LegOrderRequest(
                        "NFO_OPT|NIFTY|2026-06-09|24500|PE",
                        OptionType.PE, 24500, LegAction.SELL, new BigDecimal("50.00"), 75),
                new LegOrderRequest(
                        "NFO_OPT|NIFTY|2026-06-09|24400|PE",
                        OptionType.PE, 24400, LegAction.BUY,  new BigDecimal("25.00"), 75)
        ));

        ResponseEntity<ExecuteTradeResponse> response = restClient()
                .post()
                .uri("/api/v1/agent5/execute")
                .body(request)
                .retrieve()
                .toEntity(ExecuteTradeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ExecuteTradeResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.executionStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(body.tradeId()).isEqualTo(UNKNOWN_TRADE_ID);
        assertThat(body.rejectionReason())
                .as("Service must explain WHY it rejected")
                .isNotBlank();
    }

    // ── POST /margin/check — validation and service failures ─────────────────

    @Test
    @Order(8)
    @DisplayName("POST /margin/check missing tradeId → 400")
    void marginCheck_missingTradeId_returns400() {
        String body = "{}";  // no tradeId field
        assertBadRequest("/api/v1/agent5/margin/check", body, "missing tradeId");
    }

    @Test
    @Order(9)
    @DisplayName("POST /margin/check unknown tradeId → 502 (trade not found in DB)")
    void marginCheck_unknownTradeId_returns502() {
        // Service throws MarginCheckException when trade is not found → controller returns BAD_GATEWAY
        String body = """
                { "tradeId": "%s" }
                """.formatted(UNKNOWN_TRADE_ID);
        try {
            restClient()
                    .post()
                    .uri("/api/v1/agent5/margin/check")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            Assertions.fail("Expected 502 for unknown tradeId — got 2xx instead");
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }
    }

    // ── POST /exit — validation ───────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("POST /exit/{tradeId} path≠body tradeId → 400")
    void exit_tradeIdMismatch_returns400() {
        // PATH_TRADE_ID ≠ BODY_TRADE_ID → controller returns 400 before calling service
        ExitTradeRequest request = new ExitTradeRequest(
                BODY_TRADE_ID,
                "T3_EXIT_BREACH",
                List.of(new ExitTradeRequest.ExitLeg(
                        "NFO_OPT|NIFTY|2026-06-09|24500|PE", LegAction.SELL, 75))
        );

        assertThat(PATH_TRADE_ID).isNotEqualTo(BODY_TRADE_ID); // sanity
        assertBadRequestForExit(PATH_TRADE_ID, request, "tradeId mismatch");
    }

    @Test
    @Order(7)
    @DisplayName("POST /exit missing reason → 400")
    void exit_missingReason_returns400() {
        String body = """
                {
                    "tradeId": "%s",
                    "exitLegs": [{
                        "instrumentKey": "NFO_OPT|NIFTY|2026-06-09|24500|PE",
                        "originalAction": "SELL", "quantity": 75
                    }]
                }
                """.formatted(UNKNOWN_TRADE_ID);
        assertBadRequest("/api/v1/agent5/exit/" + UNKNOWN_TRADE_ID, body, "missing reason");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertBadRequest(String uri, String jsonBody, String description) {
        try {
            restClient()
                    .post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .toBodilessEntity();
            Assertions.fail("Expected 400 for: " + description + " — got 2xx instead");
        } catch (RestClientResponseException ex) {
            assertThat(ex.getStatusCode())
                    .as("Expected 400 for: " + description)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    private void assertBadRequestForExit(UUID pathId, ExitTradeRequest request, String description) {
        try {
            restClient()
                    .post()
                    .uri("/api/v1/agent5/exit/{tradeId}", pathId)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            Assertions.fail("Expected 400 for: " + description + " — got 2xx instead");
        } catch (RestClientResponseException ex) {
            assertThat(ex.getStatusCode())
                    .as("Expected 400 for: " + description)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
