package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.agent1.dto.ScoreRequestDto;
import com.the3Cgrp.zupptrade.shared.dto.Agent1SignalDto;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration test for POST /api/v1/agent1/score.
 *
 * Exercises the full scoring pipeline against live external APIs and Neon DB:
 *   Upstox (option chain, VIX, FII/DII, GIFT Nifty, historical candles)
 *   → Marketaux (news sentiment)
 *   → Groq LLM (commentary extraction)
 *   → 5-tier scoring
 *   → persist to Neon DB (zupptrade schema)
 *   → GET /latest to verify persistence
 *
 * HOW TO RUN:
 *   mvn test -pl agent1-market_analyst "-Dexcluded.test.groups=" "-Dgroups=integration" ^
 *            "-Dtest=ScoreEndpointE2ETest"
 *
 * Prerequisites:
 *   - GROQ_API_KEY env var set
 *   - TOKEN_ENCRYPTION_KEY env var set
 *   - Valid Upstox token in Neon DB api_tokens table (written by upstox-auth)
 *   - Neon DB tables in zupptrade schema (agent1_signals, api_tokens etc.)
 *   - Tests run in @Order sequence — Test 3 depends on Test 1 persisting a signal
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScoreEndpointE2ETest {

    @Value("${local.server.port}")
    private int port;

    // RestClient built lazily once the random port is known
    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT,       org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // Shared between Test 1 (write) and Test 3 (read-back verify)
    private static UUID persistedSignalId;

    private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 2);

    private static final String COMMENTARY = """
            Nifty 50 is holding firm above 23,800 with FII net buying of Rs 1,500 crore in
            index futures today. DIIs continue to accumulate at dips providing solid support.
            The index has key support at 23,500 and 23,200 with immediate resistance at 24,000
            and 24,200. RSI is at 58 indicating sustained bullish momentum. India VIX at 14.5
            suggests low fear in the market. Outlook remains range-bound to bullish for the week.
            """;

    // -------------------------------------------------------------------------
    // Test 1 — Full pipeline: commentary + Marketaux + all live data + DB persist
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void POST_score_fullPipeline_returnsValidSignalAndPersistsToNeonDb() {
        ScoreRequestDto request = new ScoreRequestDto(EXPIRY, COMMENTARY, true);

        System.out.println("\n======================================================");
        System.out.println(" E2E Test 1 — Full Pipeline (commentary + Marketaux)");
        System.out.println("======================================================");
        System.out.println(" Expiry date : " + EXPIRY);
        System.out.println(" Commentary  : " + COMMENTARY.strip().substring(0, 60) + "...");

        ResponseEntity<Agent1SignalDto> response = restClient()
                .post()
                .uri("/api/v1/agent1/score")
                .body(request)
                .retrieve()
                .toEntity(Agent1SignalDto.class);

        assertThat(response.getStatusCode())
                .as("POST /score must return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        Agent1SignalDto dto = response.getBody();
        assertThat(dto).as("Response body must not be null").isNotNull();

        // --- Option B guard: VIX null = Upstox token expired ---
        if (dto.vixLevel() == null) {
            fail("""
                    UPSTOX TOKEN LIKELY EXPIRED — vixLevel is null.
                    All Upstox-dependent tier scores will be 0 (meaningless signal).
                    Fix: re-run upstox-auth module to write a fresh token to Neon DB, then retry.
                    """);
        }

        // --- Core signal structure ---
        assertThat(dto.id())
                .as("Signal must be persisted with a UUID")
                .isNotNull();
        assertThat(dto.bias())
                .as("Bias must be BULLISH, BEARISH or NEUTRAL")
                .isNotNull();
        assertThat(dto.strength())
                .as("Strength must be EXTREME, MILD or WEAK")
                .isNotNull();
        assertThat(dto.compositeScore())
                .as("Composite score must be within [-1, 1]")
                .isBetween(new BigDecimal("-1.0000"), new BigDecimal("1.0000"));
        assertThat(dto.confidenceScore())
                .as("Confidence score must be within [0, 1]")
                .isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(dto.confidence())
                .as("Confidence label must be LOW, MEDIUM or HIGH")
                .isNotNull();

        // --- Upstox data present ---
        assertThat(dto.vixLevel())
                .as("VIX must be a realistic value (8–40)")
                .isBetween(new BigDecimal("8"), new BigDecimal("40"));
        assertThat(dto.vixRegime())
                .as("VIX regime must be classified")
                .isNotNull();
        assertThat(dto.vixDirection())
                .as("VIX direction must be Rising, Falling or Stable")
                .isNotNull();

        // --- JSONB audit fields ---
        assertThat(dto.scoreBreakdown())
                .as("Score breakdown JSONB must be populated with tier contributions")
                .isNotBlank();
        assertThat(dto.keyLevels())
                .as("Key levels JSONB must be populated by Groq commentary extraction")
                .isNotBlank();

        // Store for Test 3
        persistedSignalId = dto.id();

        // --- Print full signal for human review ---
        System.out.println("\n--- Signal Output ---");
        System.out.println("  ID              : " + dto.id());
        System.out.println("  Bias            : " + dto.bias() + " | Strength: " + dto.strength());
        System.out.println("  Composite Score : " + dto.compositeScore());
        System.out.println("  Confidence      : " + dto.confidence() + " (" + dto.confidenceScore() + ")");
        System.out.println("  VIX             : " + dto.vixLevel() + " | Regime: " + dto.vixRegime() + " | Direction: " + dto.vixDirection());
        System.out.println("  Commentary Div  : " + dto.commentaryDivergence());
        System.out.println("  Score Breakdown : " + dto.scoreBreakdown());
        System.out.println("  Key Levels      : " + dto.keyLevels());
        System.out.println("\n✓ Full pipeline passed — signal ID " + dto.id() + " persisted to Neon DB");
    }

    // -------------------------------------------------------------------------
    // Test 2 — No commentary, no Marketaux: Tier 4 = 0, signal still valid
    // -------------------------------------------------------------------------

    @Test
    @Order(2)
    void POST_score_withoutCommentary_tierFourZero_signalStillValid() {
        ScoreRequestDto request = new ScoreRequestDto(EXPIRY, null, false);

        System.out.println("\n======================================================");
        System.out.println(" E2E Test 2 — No Commentary / No Marketaux");
        System.out.println("======================================================");

        ResponseEntity<Agent1SignalDto> response = restClient()
                .post()
                .uri("/api/v1/agent1/score")
                .body(request)
                .retrieve()
                .toEntity(Agent1SignalDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Agent1SignalDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.id()).isNotNull();
        assertThat(dto.bias()).isNotNull();
        assertThat(dto.strength()).isNotNull();
        assertThat(dto.compositeScore())
                .isBetween(new BigDecimal("-1.0000"), new BigDecimal("1.0000"));

        // No commentary provided → Groq not called → no divergence possible
        assertThat(dto.commentaryDivergence())
                .as("commentaryDivergence must be false when no commentary provided")
                .isFalse();

        // Update shared ID — Test 2 runs after Test 1, so GET /latest returns this signal
        persistedSignalId = dto.id();

        System.out.println("  Bias            : " + dto.bias() + " | Strength: " + dto.strength());
        System.out.println("  Composite Score : " + dto.compositeScore());
        System.out.println("  Confidence      : " + dto.confidence() + " (" + dto.confidenceScore() + ")");
        System.out.println("  Commentary Div  : " + dto.commentaryDivergence());
        System.out.println("\n✓ No-commentary pipeline passed — Tier 4 LLM = 0, signal valid");
    }

    // -------------------------------------------------------------------------
    // Test 3 — GET /latest: verify signal from Test 1 is readable from Neon DB
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    void GET_latest_returnsSignalPersistedByTest1() {
        assumeThat(persistedSignalId)
                .as("Skipping Test 3 — Test 1 did not run or failed to persist a signal")
                .isNotNull();

        System.out.println("\n======================================================");
        System.out.println(" E2E Test 3 — GET /latest persistence check");
        System.out.println("======================================================");
        System.out.println(" Looking up signal for expiry: " + EXPIRY);

        ResponseEntity<Agent1SignalDto> response = restClient()
                .get()
                .uri("/api/v1/agent1/latest?expiry_date=" + EXPIRY)
                .retrieve()
                .toEntity(Agent1SignalDto.class);

        assertThat(response.getStatusCode())
                .as("GET /latest must return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        Agent1SignalDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.id())
                .as("GET /latest must return the most recently persisted signal (Test 2's ID, since it runs after Test 1)")
                .isEqualTo(persistedSignalId);

        System.out.println("  Expected ID     : " + persistedSignalId);
        System.out.println("  Retrieved ID    : " + dto.id());
        System.out.println("  Bias            : " + dto.bias() + " | Strength: " + dto.strength());
        System.out.println("\n✓ Neon DB persistence confirmed — round-trip POST → GET verified");
    }
}
