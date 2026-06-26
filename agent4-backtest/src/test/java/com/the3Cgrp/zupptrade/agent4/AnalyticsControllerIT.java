package com.the3Cgrp.zupptrade.agent4;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for AnalyticsController against NeonDB (zupptrade_dev schema).
 *
 * Pattern: seed minimal closed-trade data in seedData(), clean up in cleanData().
 * HTTP calls use RestClient (Spring 6.1+) against the locally started server.
 * JSON assertions use JsonPath (jayway, already on the test classpath).
 * All tests are tagged "integration" — excluded from default mvn test runs.
 *
 * Run:
 *   mvn test -pl agent4-backtest "-Dexcluded.test.groups=" -Dgroups=integration
 *             -Dspring.profiles.active=local
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalyticsControllerIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private RestClient restClient;

    // Deterministic IDs — stable across @BeforeAll/@AfterAll non-static lifecycle
    private final UUID signalId  = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final UUID profileId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private final UUID tradeId   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeAll
    void setUp() {
        restClient = RestClient.create("http://localhost:" + port);
        cleanData(); // guard against stale rows from a prior interrupted run
        seedData();
    }

    @AfterAll
    void tearDown() {
        cleanData();
    }

    // ── /health ───────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void healthEndpointReturnsUp() {
        ResponseEntity<String> resp = restClient.get()
                .uri("/api/v1/agent4/health")
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("UP");
        assertThat((Boolean) JsonPath.read(body, "$.dbConnected")).isTrue();
        assertThat((Integer) JsonPath.read(body, "$.closedTradeCount")).isGreaterThanOrEqualTo(1);
    }

    // ── /summary ──────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void summaryReturnsPortfolioMetrics() {
        ResponseEntity<String> resp = restClient.get()
                .uri("/api/v1/agent4/summary")
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat((Integer) JsonPath.read(body, "$.totalTrades")).isGreaterThanOrEqualTo(1);
        assertFieldNotNull(body, "$.winRatePct");
        assertFieldNotNull(body, "$.totalRealizedPnl");
        assertFieldNotNull(body, "$.winRateByVixRegime");
        assertFieldNotNull(body, "$.winRateByConfidence");
    }

    @Test
    @Order(3)
    void summaryWithDateRangeFilter() {
        ResponseEntity<String> resp = restClient.get()
                .uri(u -> u.path("/api/v1/agent4/summary")
                           .queryParam("from", "2026-01-01")
                           .queryParam("to", "2026-12-31")
                           .build())
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.totalTrades")).isGreaterThanOrEqualTo(0);
    }

    @Test
    @Order(4)
    void summaryFromAfterToReturnsBadRequest() {
        assertThatThrownBy(() ->
            restClient.get()
                      .uri(u -> u.path("/api/v1/agent4/summary")
                                 .queryParam("from", "2026-12-31")
                                 .queryParam("to", "2026-01-01")
                                 .build())
                      .retrieve()
                      .toEntity(String.class)
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    // ── /trades ───────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void tradeListDefaultsToFiveTrades() {
        ResponseEntity<String> resp = restClient.get()
                .uri("/api/v1/agent4/trades")
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertFieldNotNull(body, "$.trades");
        assertThat((Integer) JsonPath.read(body, "$.size")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(body, "$.page")).isEqualTo(0);
        assertFieldNotNull(body, "$.totalCount");
        assertFieldNotNull(body, "$.hasMore");
    }

    @Test
    @Order(6)
    void tradeListWithCustomPageSize() {
        ResponseEntity<String> resp = restClient.get()
                .uri(u -> u.path("/api/v1/agent4/trades")
                           .queryParam("size", "10")
                           .queryParam("page", "0")
                           .build())
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.size")).isEqualTo(10);
    }

    @Test
    @Order(7)
    void tradeListContainsSeededTrade() {
        ResponseEntity<String> resp = restClient.get()
                .uri(u -> u.path("/api/v1/agent4/trades")
                           .queryParam("from", "2026-01-01")
                           .queryParam("to", "2026-12-31")
                           .queryParam("size", "100")
                           .queryParam("page", "0")
                           .build())
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> matched = JsonPath.read(resp.getBody(),
                "$.trades[?(@.tradeId == '" + tradeId + "')]");
        assertThat(matched).as("Seeded trade should appear in trade list").isNotEmpty();
    }

    @Test
    @Order(8)
    void tradeListInvalidSizeReturnsBadRequest() {
        assertThatThrownBy(() ->
            restClient.get()
                      .uri(u -> u.path("/api/v1/agent4/trades").queryParam("size", "0").build())
                      .retrieve()
                      .toEntity(String.class)
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    // ── /trades/{id}/audit ────────────────────────────────────────────────────

    @Test
    @Order(9)
    void tradeAuditReturnsAllFourChapters() {
        ResponseEntity<String> resp = restClient.get()
                .uri("/api/v1/agent4/trades/{id}/audit", tradeId)
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat((String) JsonPath.read(body, "$.tradeId")).isEqualTo(tradeId.toString());
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("CLOSED");
        assertFieldNotNull(body, "$.signal");
        assertFieldNotNull(body, "$.recommendation");
        assertFieldNotNull(body, "$.execution");
        assertFieldNotNull(body, "$.monitoring");
    }

    @Test
    @Order(10)
    void tradeAuditSignalChapterHasBiasAndScore() {
        ResponseEntity<String> resp = restClient.get()
                .uri("/api/v1/agent4/trades/{id}/audit", tradeId)
                .retrieve()
                .toEntity(String.class);

        String body = resp.getBody();
        assertThat((String) JsonPath.read(body, "$.signal.bias")).isEqualTo("BULLISH");
        assertThat((String) JsonPath.read(body, "$.signal.strength")).isEqualTo("MILD");
        assertFieldNotNull(body, "$.signal.compositeScore");
        assertThat((String) JsonPath.read(body, "$.signal.vixRegime")).isEqualTo("HIGH");
    }

    @Test
    @Order(11)
    void tradeAuditRecommendationChapterHasGatesAndLots() {
        ResponseEntity<String> resp = restClient.get()
                .uri("/api/v1/agent4/trades/{id}/audit", tradeId)
                .retrieve()
                .toEntity(String.class);

        String body = resp.getBody();
        assertThat((String) JsonPath.read(body, "$.recommendation.strategy"))
                .isEqualTo("BullPutSpread");
        assertFieldNotNull(body, "$.recommendation.gateResults");
        assertThat((Integer) JsonPath.read(body, "$.recommendation.lots")).isEqualTo(4);
        assertFieldNotNull(body, "$.recommendation.rocTheoreticalPct");
    }

    @Test
    @Order(12)
    void tradeAuditUnknownIdReturns404() {
        assertThatThrownBy(() ->
            restClient.get()
                      .uri("/api/v1/agent4/trades/{id}/audit",
                            UUID.fromString("00000000-dead-dead-dead-000000000000"))
                      .retrieve()
                      .toEntity(String.class)
        ).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @Order(13)
    void tradeAuditInvalidUuidReturns400() {
        assertThatThrownBy(() ->
            restClient.get()
                      .uri("/api/v1/agent4/trades/not-a-uuid/audit")
                      .retrieve()
                      .toEntity(String.class)
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    // ── /signal-quality ───────────────────────────────────────────────────────

    @Test
    @Order(14)
    void signalQualityReturnsStructuredReport() {
        ResponseEntity<String> resp = restClient.get()
                .uri("/api/v1/agent4/signal-quality")
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertFieldNotNull(body, "$.totalSignals");
        assertFieldNotNull(body, "$.signalsLeadingToTrade");
        assertFieldNotNull(body, "$.overallAccuracyPct");
        assertFieldNotNull(body, "$.accuracyByConfidence");
        assertFieldNotNull(body, "$.accuracyByBias");
    }

    @Test
    @Order(15)
    void signalQualityWithDateFilter() {
        ResponseEntity<String> resp = restClient.get()
                .uri(u -> u.path("/api/v1/agent4/signal-quality")
                           .queryParam("from", "2026-01-01")
                           .queryParam("to", "2026-12-31")
                           .build())
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertFieldNotNull(resp.getBody(), "$.totalSignals");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Avoids AssertJ overload ambiguity from JsonPath.read() generic return type. */
    private static void assertFieldNotNull(String json, String path) {
        Object val = JsonPath.read(json, path);
        assertThat(val).isNotNull();
    }

    // ── Seed / Cleanup ────────────────────────────────────────────────────────

    private void seedData() {
        // 1. User profile
        jdbc.update("""
            INSERT INTO zupptrade_dev.user_profiles
              (id, user_id, capital, min_pop, max_loss_pct, max_pop_popp_gap)
            VALUES (?, 'IT_TEST_USER', 500000.00, 0.80, 1.50, 15.0)
            ON CONFLICT (user_id) DO NOTHING
            """, profileId);

        // 2. Agent 1 signal
        jdbc.update("""
            INSERT INTO zupptrade_dev.agent1_signals
              (id, timestamp, expiry_date, bias, strength, composite_score,
               confidence, confidence_label, vix_level, vix_regime, vix_direction,
               score_breakdown, data_gaps, commentary_divergence, status, created_at)
            VALUES (?, NOW(), '2026-06-17', 'BULLISH', 'MILD', 0.3100,
                    0.68, 'MEDIUM', 18.50, 'HIGH', 'FALLING',
                    '{"tier1a":0.40,"tier1b":0.30,"tier2":0.45,"tier3":0.20,"tier4":0.10}'::jsonb,
                    '[]'::jsonb, false, 'EXPIRED', NOW())
            ON CONFLICT DO NOTHING
            """, signalId);

        // 3. Closed trade.
        //    JSONB key names must match Jackson-serialised record field names:
        //      TradeSummary: "roc" (not "rocTheoreticalPct"), "theoreticalMaxLossTotal"
        //      MarketContext: "vix" (not "vixLevel"), enum names as strings for ivRegime/vixRegime
        jdbc.update("""
            INSERT INTO zupptrade_dev.trades
              (id, agent1_signal_id, user_profile_id, status, strategy, spread_direction,
               expiry_date, trade_code, dte,
               legs, summary, market_context, gate_results, thresholds, monitor_config,
               entry_fills, generated_at, confirmed_at, closed_at, close_reason,
               actual_pnl, created_at)
            VALUES (
              ?, ?, ?, 'CLOSED', 'BullPutSpread', 'SELL',
              '2026-06-17', 'T-20260617-9999', 5,
              '[{"action":"SELL","strike":23400,"type":"PE","ltp":68.40,"iv":18.5,"delta":-0.169,"theta":-12.4,"vega":18.6,"pop":0.17},
                {"action":"BUY","strike":23300,"type":"PE","ltp":45.20,"iv":19.1,"delta":-0.142,"theta":-10.1,"vega":15.9,"pop":0.14}]'::jsonb,
              '{"netPremiumPerUnit":23.20,"lots":4,"lotSize":75,
                "maxProfitTotal":6960,"theoreticalMaxLossTotal":24040,"realExpectedLossTotal":12020,
                "pop":83.10,"popp":86.20,"popGap":3.10,"roc":1.63,
                "rocAnnualised":78.1,"netDelta":-0.05}'::jsonb,
              '{"spot":23412.60,"vix":18.50,"atmIv":18.5,"historicalVolatility":15.0,
                "ivHvRatio":1.23,"ivRegime":"RICH","vixRegime":"HIGH",
                "expectedMove":250.0,"oneFourSdBoundary":23163.0}'::jsonb,
              '[{"gate":"G1","passed":true,"description":"PoP >= 80%","value":83.10,"threshold":80.0},
                {"gate":"G2","passed":true,"description":"Max loss <= 1.5% capital","value":12020,"threshold":7500},
                {"gate":"G3","passed":true,"description":"PoPP gap <= 15%","value":3.10,"threshold":15.0},
                {"gate":"G4","passed":true,"description":"RoC >= 0.5%","value":1.63,"threshold":0.50}]'::jsonb,
              '{"t1WatchNiftyLevel":23550,"t2ReadjustNiftyLevel":23475,"t3ExitNiftyLevel":23400,
                "t2LossThreshold":6010,"t3LossThreshold":12020}'::jsonb,
              '{}'::jsonb,
              '{"leg1FillPrice":68.00,"leg2FillPrice":45.50,"actualNetPremium":22.50}'::jsonb,
              NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days',
              NOW() - INTERVAL '2 days', 'T3_EXIT_SPOT_BREACH', 5520.00, NOW() - INTERVAL '5 days'
            )
            ON CONFLICT (id) DO NOTHING
            """, tradeId, signalId, profileId);
    }

    private void cleanData() {
        jdbc.update("DELETE FROM zupptrade_dev.trades WHERE id = ?",         tradeId);
        jdbc.update("DELETE FROM zupptrade_dev.agent1_signals WHERE id = ?", signalId);
        jdbc.update("DELETE FROM zupptrade_dev.user_profiles WHERE id = ?",  profileId);
    }
}
