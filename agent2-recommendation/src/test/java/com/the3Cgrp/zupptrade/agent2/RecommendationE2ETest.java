package com.the3Cgrp.zupptrade.agent2;

import com.the3Cgrp.zupptrade.agent2.db.TestFlywayConfig;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.RecommendRequestDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeConfirmRequestDto;
import com.the3Cgrp.zupptrade.shared.enums.ConfirmAction;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Multi-scenario end-to-end integration test for Agent 2 recommendation pipeline.
 *
 * <p><b>No values are mocked.</b> All data comes from live sources:
 * <ul>
 *   <li>Upstox /v2/market-quote/ltp  — live Nifty spot + India VIX</li>
 *   <li>Upstox /v2/option/chain      — live strikes, LTP, IV, Greeks, PoP</li>
 *   <li>Upstox /v2/historical-candle — live 20-day historical volatility (HV)</li>
 *   <li>Neon PostgreSQL              — Flyway-migrated schema, live reads/writes</li>
 * </ul>
 *
 * <p>Only the Agent 1 signal rows are seeded — bias, strength, confidence, and vix_regime
 * are controlled inputs that define each scenario. Everything derived from those inputs
 * (IV regime, expected move, strikes, gate results) is computed live.
 *
 * <p>Tests fail loudly on any live-data failure (expired token, network error, empty chain)
 * with a diagnostic message pointing to the most likely cause.
 *
 * <p>Exercises 4 distinct market scenarios:
 * <ol>
 *   <li><b>BULL_PUT_SPREAD</b>  — BULLISH + MILD  + NORMAL VIX + live RICH IV (credit)</li>
 *   <li><b>BEAR_CALL_SPREAD</b> — BEARISH + MILD  + NORMAL VIX + live RICH IV (credit)</li>
 *   <li><b>BULL_CALL_SPREAD</b> — BULLISH + EXTREME + HIGH VIX  (debit — IV regime irrelevant)</li>
 *   <li><b>IRON_CONDOR</b>      — NEUTRAL + WEAK  + NORMAL VIX + live RICH IV (credit)</li>
 * </ol>
 *
 * <p>Each scenario is self-contained: seeds its own Agent 1 signal, runs /recommend end-to-end,
 * then if the trade passes all gates (PENDING_CONFIRM) also runs /confirm and /monitor-config.
 * Gate rejections (e.g. thin RoC on short DTE) are accepted as correct engine behaviour.
 *
 * <p><b>Strategy selector note:</b> {@code ctx.getSignal().getVixRegime()} determines the
 * VIX regime branch in the decision matrix — it comes from the seeded signal row, not the
 * live VIX value. The live VIX is used only to compute the IV/HV ratio (IV regime).
 *
 * <p><b>HOW TO RUN:</b>
 * <pre>
 *   mvn test -pl agent2-recommendation "-Dexcluded.test.groups=" "-Dgroups=integration" ^
 *            "-Dtest=RecommendationE2ETest"
 * </pre>
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>TOKEN_ENCRYPTION_KEY env var set</li>
 *   <li>Valid Upstox token in Neon DB api_tokens table</li>
 *   <li>Neon DB reachable via application-local.yml</li>
 * </ul>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import(TestFlywayConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecommendationE2ETest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Nearest upcoming Tuesday — Nifty weekly expiry. Computed once at class load. */
    private static final LocalDate EXPIRY = nextTuesdayExpiry();

    /** Seeded by @BeforeAll — shared across all 4 scenarios. */
    private UUID userProfileId;

    // =========================================================================
    // Shared setup
    // =========================================================================

    @BeforeAll
    void seedSharedTestData() {
        // Seed lot size once — shared by all scenarios.
        // TTL 720h (30 days) ensures it does not expire mid-run.
        jdbcTemplate.update("""
                INSERT INTO reference_data (key, value, source, fetched_at, ttl_hours)
                VALUES ('nifty.lot.size', '{"lotSize": 65}'::jsonb, 'E2E_TEST', NOW(), 720)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, fetched_at = NOW()
                """);

        // V2 migration seeds one default user profile.
        userProfileId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_profiles WHERE user_id = 'default'",
                UUID.class);

        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   Agent 2 Recommendation — Multi-Scenario E2E Test Suite    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf("  Expiry Date   : %s (DTE = %d calendar days)%n",
                EXPIRY, java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), EXPIRY));
        System.out.printf("  User Profile  : %s%n", userProfileId);
        System.out.println("  Live data     : Upstox spot + VIX + option chain + 20-day HV");
        System.out.println("  Mocked        : nothing — all values are live");
        System.out.println("─────────────────────────────────────────────────────────────");
    }

    // =========================================================================
    // Scenario 1 — BULL_PUT_SPREAD
    // Market: Bullish, Mild, Normal VIX regime, live RICH IV → sell put below spot
    // =========================================================================

    @Test
    @Order(1)
    void scenario1_BULL_PUT_SPREAD_bullishMildNormalVix() {
        printScenarioHeader(1, "BULL_PUT_SPREAD",
                "BULLISH | MILD | vix_regime=NORMAL | live spot + VIX from Upstox");

        // Seed: Bullish Mild signal — composite_score in [0.25, 0.50] range
        // vix_regime=NORMAL → StrategySelector picks BULL_PUT_SPREAD when IV is RICH
        UUID signalId = seedSignal("BULLISH", "MILD", "MEDIUM", 0.35, "NORMAL");

        TradeCardDto card = runRecommend(signalId);

        // --- Strategy ---
        assertThat(card.strategy())
                .as("S1: Expected BULL_PUT_SPREAD for BULLISH+MILD+NORMAL+RICH")
                .isEqualTo(Strategy.BULL_PUT_SPREAD);
        assertThat(card.spreadDirection())
                .as("S1: BULL_PUT_SPREAD is a CREDIT spread")
                .isEqualTo(SpreadDirection.CREDIT);

        // --- Legs: both PE; short put has higher strike than long put ---
        assertThat(card.shortLeg().optionType())
                .as("S1: Short leg must be PE (put)")
                .isEqualTo(OptionType.PE);
        assertThat(card.shortLeg().action())
                .as("S1: Short leg must be SELL")
                .isEqualTo(LegAction.SELL);
        assertThat(card.longLeg().optionType())
                .as("S1: Long leg must be PE (put)")
                .isEqualTo(OptionType.PE);
        assertThat(card.shortLeg().strike())
                .as("S1: Short put must be at higher strike than long put (short closer to spot)")
                .isGreaterThan(card.longLeg().strike());

        // --- Common assertions ---
        assertCommonTradeCard(card, "S1");

        printTradeCard(card, 1);

        // --- Confirm + monitor-config if gates passed ---
        if (card.status() == TradeStatus.PENDING_CONFIRM) {
            TradeCardDto confirmed = runConfirm(card.tradeId());
            assertThat(confirmed.status()).isEqualTo(TradeStatus.CONFIRMED);

            // Fill prices: short PE fill slightly below LTP, long PE fill slightly above LTP
            BigDecimal shortFill = card.shortLeg().ltp().multiply(new BigDecimal("0.95"))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal longFill = card.longLeg().ltp().multiply(new BigDecimal("1.05"))
                    .setScale(2, RoundingMode.HALF_UP);

            MonitorConfigDto monitor = runMonitorConfig(card.tradeId(), shortFill, longFill);
            assertMonitorConfig(monitor, card.tradeId(), "S1");
            printMonitorConfig(monitor);
        }
    }

    // =========================================================================
    // Scenario 2 — BEAR_CALL_SPREAD
    // Market: Bearish, Mild, Normal VIX regime, live RICH IV → sell call above spot
    // =========================================================================

    @Test
    @Order(2)
    void scenario2_BEAR_CALL_SPREAD_bearishMildNormalVix() {
        printScenarioHeader(2, "BEAR_CALL_SPREAD",
                "BEARISH | MILD | vix_regime=NORMAL | live spot + VIX from Upstox");

        // Seed: Bearish Mild signal — composite_score in [-0.50, -0.25] range
        UUID signalId = seedSignal("BEARISH", "MILD", "MEDIUM", -0.35, "NORMAL");

        TradeCardDto card = runRecommend(signalId);

        // --- Strategy ---
        assertThat(card.strategy())
                .as("S2: Expected BEAR_CALL_SPREAD for BEARISH+MILD+NORMAL+RICH")
                .isEqualTo(Strategy.BEAR_CALL_SPREAD);
        assertThat(card.spreadDirection())
                .as("S2: BEAR_CALL_SPREAD is a CREDIT spread")
                .isEqualTo(SpreadDirection.CREDIT);

        // --- Legs: both CE; short call has lower strike than long call ---
        assertThat(card.shortLeg().optionType())
                .as("S2: Short leg must be CE (call)")
                .isEqualTo(OptionType.CE);
        assertThat(card.shortLeg().action())
                .as("S2: Short leg must be SELL")
                .isEqualTo(LegAction.SELL);
        assertThat(card.longLeg().optionType())
                .as("S2: Long leg must be CE (call)")
                .isEqualTo(OptionType.CE);
        assertThat(card.shortLeg().strike())
                .as("S2: Short call must be at lower strike than long call (short closer to spot)")
                .isLessThan(card.longLeg().strike());

        // --- Common assertions ---
        assertCommonTradeCard(card, "S2");

        printTradeCard(card, 2);

        // --- Confirm + monitor-config if gates passed ---
        if (card.status() == TradeStatus.PENDING_CONFIRM) {
            TradeCardDto confirmed = runConfirm(card.tradeId());
            assertThat(confirmed.status()).isEqualTo(TradeStatus.CONFIRMED);

            BigDecimal shortFill = card.shortLeg().ltp().multiply(new BigDecimal("0.95"))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal longFill = card.longLeg().ltp().multiply(new BigDecimal("1.05"))
                    .setScale(2, RoundingMode.HALF_UP);

            MonitorConfigDto monitor = runMonitorConfig(card.tradeId(), shortFill, longFill);
            assertMonitorConfig(monitor, card.tradeId(), "S2");
            printMonitorConfig(monitor);
        }
    }

    // =========================================================================
    // Scenario 3 — BULL_CALL_SPREAD (debit)
    // Market: Bullish, Extreme, High VIX regime → EXTREME strength bypasses IV regime check
    // Long ATM call + short OTM call (200 pts spread)
    // =========================================================================

    @Test
    @Order(3)
    void scenario3_BULL_CALL_SPREAD_bullishExtremeHighVix() {
        printScenarioHeader(3, "BULL_CALL_SPREAD (debit)",
                "BULLISH | EXTREME | vix_regime=HIGH | live spot + VIX from Upstox");

        // Seed: Bullish Extreme signal — composite_score > 0.50
        // vix_regime=HIGH → EXTREME strength always selects BULL_CALL_SPREAD regardless of IV regime
        UUID signalId = seedSignal("BULLISH", "EXTREME", "MEDIUM", 0.65, "HIGH");

        TradeCardDto card = runRecommend(signalId);

        // --- Strategy ---
        assertThat(card.strategy())
                .as("S3: Expected BULL_CALL_SPREAD for BULLISH+EXTREME (IV regime irrelevant)")
                .isEqualTo(Strategy.BULL_CALL_SPREAD);
        assertThat(card.spreadDirection())
                .as("S3: BULL_CALL_SPREAD is a DEBIT spread")
                .isEqualTo(SpreadDirection.DEBIT);

        // --- Legs: long ATM call (BUY, lower strike) + short OTM call (SELL, 200 pts higher) ---
        assertThat(card.longLeg().optionType())
                .as("S3: Long leg must be CE (call)")
                .isEqualTo(OptionType.CE);
        assertThat(card.longLeg().action())
                .as("S3: Long leg must be BUY")
                .isEqualTo(LegAction.BUY);
        assertThat(card.shortLeg().optionType())
                .as("S3: Short leg must be CE (call)")
                .isEqualTo(OptionType.CE);
        assertThat(card.shortLeg().action())
                .as("S3: Short leg must be SELL")
                .isEqualTo(LegAction.SELL);
        assertThat(card.shortLeg().strike())
                .as("S3: Short OTM call must be at higher strike than long ATM call (debit spread)")
                .isGreaterThan(card.longLeg().strike());

        // G3 is N/A for debit spreads — must be marked passed
        card.gateResults().stream()
                .filter(g -> "G3".equals(g.gate()))
                .findFirst()
                .ifPresent(g3 -> assertThat(g3.passed())
                        .as("S3: G3 must be passed/N/A for debit spread")
                        .isTrue());

        // --- Common assertions ---
        assertCommonTradeCard(card, "S3");

        printTradeCard(card, 3);

        // --- Confirm + monitor-config if gates passed ---
        if (card.status() == TradeStatus.PENDING_CONFIRM) {
            TradeCardDto confirmed = runConfirm(card.tradeId());
            assertThat(confirmed.status()).isEqualTo(TradeStatus.CONFIRMED);

            // Debit spread: long fill > short fill (long is ATM = more expensive)
            BigDecimal shortFill = card.shortLeg().ltp().multiply(new BigDecimal("1.05"))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal longFill = card.longLeg().ltp().multiply(new BigDecimal("0.95"))
                    .setScale(2, RoundingMode.HALF_UP);

            MonitorConfigDto monitor = runMonitorConfig(card.tradeId(), shortFill, longFill);
            assertMonitorConfig(monitor, card.tradeId(), "S3");
            printMonitorConfig(monitor);
        }
    }

    // =========================================================================
    // Scenario 4 — IRON_CONDOR
    // Market: Neutral, Weak, Normal VIX regime, live RICH IV → dual credit spread
    // Current implementation stores the call spread legs in ctx after selectIronCondor.
    // Both strikes are CE; the call side of the condor is what is sized and monitored.
    // =========================================================================

    @Test
    @Order(4)
    void scenario4_IRON_CONDOR_neutralWeakNormalVix() {
        printScenarioHeader(4, "IRON_CONDOR",
                "NEUTRAL | WEAK | vix_regime=NORMAL | live spot + VIX from Upstox");

        // Seed: Neutral Weak signal — composite_score in [-0.10, 0.10] range
        UUID signalId = seedSignal("NEUTRAL", "WEAK", "MEDIUM", 0.05, "NORMAL");

        TradeCardDto card = runRecommend(signalId);

        // --- Strategy ---
        assertThat(card.strategy())
                .as("S4: Expected IRON_CONDOR for NEUTRAL+WEAK+NORMAL+RICH")
                .isEqualTo(Strategy.IRON_CONDOR);
        assertThat(card.spreadDirection())
                .as("S4: IRON_CONDOR is a CREDIT spread")
                .isEqualTo(SpreadDirection.CREDIT);

        // The call spread legs are stored in ctx after selectIronCondor.
        // Both CE; short call (SELL, lower) is the premium-collecting leg.
        assertThat(card.shortLeg().optionType())
                .as("S4: Iron condor stores call spread — short leg must be CE")
                .isEqualTo(OptionType.CE);
        assertThat(card.shortLeg().action())
                .as("S4: Short leg must be SELL")
                .isEqualTo(LegAction.SELL);
        assertThat(card.shortLeg().strike())
                .as("S4: Iron condor short call must be below long call (short closer to spot)")
                .isLessThan(card.longLeg().strike());

        // --- Common assertions ---
        assertCommonTradeCard(card, "S4");

        printTradeCard(card, 4);

        // --- Confirm + monitor-config if gates passed ---
        if (card.status() == TradeStatus.PENDING_CONFIRM) {
            TradeCardDto confirmed = runConfirm(card.tradeId());
            assertThat(confirmed.status()).isEqualTo(TradeStatus.CONFIRMED);

            BigDecimal shortFill = card.shortLeg().ltp().multiply(new BigDecimal("0.95"))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal longFill = card.longLeg().ltp().multiply(new BigDecimal("1.05"))
                    .setScale(2, RoundingMode.HALF_UP);

            MonitorConfigDto monitor = runMonitorConfig(card.tradeId(), shortFill, longFill);
            assertMonitorConfig(monitor, card.tradeId(), "S4");
            printMonitorConfig(monitor);
        }
    }

    // =========================================================================
    // Shared assertion helpers
    // =========================================================================

    /**
     * Assertions that apply to every scenario regardless of strategy or gate outcome.
     * Financial totals (maxProfit, maxLoss, lots) are only asserted when status=PENDING_CONFIRM
     * because Layer 5 (PositionSizer) does not run for gate-rejected trades.
     */
    private void assertCommonTradeCard(TradeCardDto card, String scenario) {
        assertThat(card.tradeId())
                .as(scenario + ": tradeId must be a persisted UUID")
                .isNotNull();
        assertThat(card.status())
                .as(scenario + ": status must be PENDING_CONFIRM or REJECTED (gate logic)")
                .isIn(TradeStatus.PENDING_CONFIRM, TradeStatus.REJECTED);
        assertThat(card.expiryDate())
                .as(scenario + ": expiry must match the seeded signal")
                .isEqualTo(EXPIRY);
        assertThat(card.dte())
                .as(scenario + ": DTE must be > 0 for a future expiry")
                .isGreaterThan(0);
        assertThat(card.shortLeg())
                .as(scenario + ": shortLeg must be populated by StrikeSelector")
                .isNotNull();
        assertThat(card.longLeg())
                .as(scenario + ": longLeg must be populated by StrikeSelector")
                .isNotNull();
        assertThat(card.shortLeg().strike())
                .as(scenario + ": short strike must be a realistic Nifty level (> 10,000)")
                .isGreaterThan(10_000);
        assertThat(card.longLeg().strike())
                .as(scenario + ": long strike must be a realistic Nifty level (> 10,000)")
                .isGreaterThan(10_000);
        assertThat(card.lotSize())
                .as(scenario + ": lot size must be 65 (seeded in reference_data)")
                .isEqualTo(65);

        // netPremiumPerUnit is always set — computed from leg LTPs in Layer 3 (before gates run)
        assertThat(card.netPremiumPerUnit())
                .as(scenario + ": net premium per unit must be positive (from live leg LTPs)")
                .isGreaterThan(BigDecimal.ZERO);

        // PoP comes from Upstox Greeks — always present after Layer 3
        assertThat(card.pop())
                .as(scenario + ": PoP must be between 0 and 100")
                .isBetween(BigDecimal.ZERO, new BigDecimal("100"));

        // Gate results populated by Layer 4 (G1–G3) and Layer 5 (G4 appended when sizing runs)
        assertThat(card.gateResults())
                .as(scenario + ": gate results must be non-empty (at least G1–G3)")
                .isNotNull()
                .isNotEmpty();

        assertThat(card.generatedAt())
                .as(scenario + ": generatedAt must be set")
                .isNotNull();
        assertThat(card.validUntil())
                .as(scenario + ": validUntil must be after generatedAt (20-min window)")
                .isAfter(card.generatedAt());

        // Financial totals and lot count are only populated when Layer 5 runs (gates passed)
        if (card.status() == TradeStatus.PENDING_CONFIRM) {
            assertThat(card.lots())
                    .as(scenario + ": lots must be at least 1 when all gates passed")
                    .isGreaterThanOrEqualTo(1);
            assertThat(card.maxProfitTotal())
                    .as(scenario + ": max profit must be positive when all gates passed")
                    .isGreaterThan(BigDecimal.ZERO);
            assertThat(card.theoreticalMaxLossTotal())
                    .as(scenario + ": max loss must be positive when all gates passed")
                    .isGreaterThan(BigDecimal.ZERO);
        }
    }

    private void assertMonitorConfig(MonitorConfigDto config, UUID expectedTradeId, String scenario) {
        assertThat(config).as(scenario + ": MonitorConfigDto must not be null").isNotNull();
        assertThat(config.tradeId()).as(scenario + ": tradeId must match").isEqualTo(expectedTradeId);
        assertThat(config.strategy()).as(scenario + ": strategy must be carried through").isNotNull();
        assertThat(config.shortLeg()).as(scenario + ": short leg must be set").isNotNull();
        assertThat(config.longLeg()).as(scenario + ": long leg must be set").isNotNull();
        assertThat(config.actualNetPremiumPerUnit())
                .as(scenario + ": actual net premium must be positive")
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(config.maxProfitTotal())
                .as(scenario + ": max profit must be positive")
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(config.actualMaxLossTotal())
                .as(scenario + ": max loss must be positive (stored as magnitude)")
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(config.slippageAmount())
                .as(scenario + ": slippage must be ≥ 0")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(config.lots())
                .as(scenario + ": lots must be ≥ 1")
                .isGreaterThanOrEqualTo(1);
        assertThat(config.lotSize()).isEqualTo(65);
        assertThat(config.thresholds())
                .as(scenario + ": monitor thresholds must be set")
                .isNotNull();
        assertThat(config.expiryDate()).isEqualTo(EXPIRY);
        assertThat(config.dte())
                .as(scenario + ": DTE must be > 0")
                .isGreaterThan(0);
    }

    // =========================================================================
    // REST call helpers
    // =========================================================================

    private TradeCardDto runRecommend(UUID signalId) {
        RecommendRequestDto request = RecommendRequestDto.standard(userProfileId, signalId);
        try {
            ResponseEntity<TradeCardDto> response = restClient()
                    .post()
                    .uri("/api/v1/agent2/recommend")
                    .body(request)
                    .retrieve()
                    .toEntity(TradeCardDto.class);

            assertThat(response.getStatusCode())
                    .as("POST /recommend must return HTTP 200")
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as("Response body must not be null").isNotNull();
            return response.getBody();

        } catch (RestClientResponseException ex) {
            fail("""
                    POST /recommend failed with HTTP %d.
                    Body: %s

                    Likely causes:
                      1. Upstox token expired — re-run upstox-auth to refresh the token
                         in the Neon DB api_tokens table, then retry.
                      2. Option chain empty for expiry %s — verify it is a valid
                         Nifty weekly Tuesday expiry.
                      3. Lot size missing from reference_data — check @BeforeAll seed ran.
                      4. Live spot/VIX unavailable — check Upstox /v2/market-quote/ltp.
                    """.formatted(ex.getStatusCode().value(), ex.getResponseBodyAsString(), EXPIRY));
            return null; // unreachable
        }
    }

    private TradeCardDto runConfirm(UUID tradeId) {
        TradeConfirmRequestDto request = new TradeConfirmRequestDto(tradeId, ConfirmAction.CONFIRM, null, null, null);
        ResponseEntity<TradeCardDto> response = restClient()
                .post()
                .uri("/api/v1/agent2/confirm")
                .body(request)
                .retrieve()
                .toEntity(TradeCardDto.class);

        assertThat(response.getStatusCode())
                .as("POST /confirm must return HTTP 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("Confirmed trade card must not be null").isNotNull();
        return response.getBody();
    }

    private MonitorConfigDto runMonitorConfig(UUID tradeId,
                                               BigDecimal shortFillPrice,
                                               BigDecimal longFillPrice) {
        ResponseEntity<MonitorConfigDto> response = restClient()
                .get()
                .uri("/api/v1/agent2/monitor-config/" + tradeId)
                .header("X-Short-Fill-Price", shortFillPrice.toPlainString())
                .header("X-Long-Fill-Price", longFillPrice.toPlainString())
                .retrieve()
                .toEntity(MonitorConfigDto.class);

        assertThat(response.getStatusCode())
                .as("GET /monitor-config must return HTTP 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("MonitorConfigDto must not be null").isNotNull();
        return response.getBody();
    }

    // =========================================================================
    // DB seed helper
    // =========================================================================

    /**
     * Seeds one Agent 1 signal row for the given scenario inputs.
     *
     * <p>Only the decision-matrix inputs are controlled: bias, strength, confidenceLabel,
     * compositeScore, and vixRegime. Everything else (spot, VIX, IV regime, EM, strikes)
     * is derived live at recommendation time.
     *
     * <p>The {@code vix_level} column is nullable and informational — it is not used by the
     * recommendation engine (the engine uses live VIX from Upstox at recommendation time).
     *
     * @param bias            "BULLISH", "BEARISH", or "NEUTRAL"
     * @param strength        "MILD", "EXTREME", or "WEAK"
     * @param confidenceLabel "MEDIUM" or "HIGH" (LOW causes NO_TRADE)
     * @param compositeScore  numeric score consistent with bias+strength per CLAUDE.md spec
     * @param vixRegime       "NORMAL", "HIGH", or "EXTREME" — stored in vix_regime column,
     *                        read by StrategySelector to determine strategy branch
     * @return UUID of the newly inserted signal row
     */
    private UUID seedSignal(String bias, String strength, String confidenceLabel,
                             double compositeScore, String vixRegime) {
        jdbcTemplate.update("""
                INSERT INTO agent1_signals
                    (timestamp, expiry_date, bias, strength, composite_score, confidence,
                     confidence_label, vix_regime, vix_direction,
                     commentary_divergence, status, created_at)
                VALUES (NOW(), ?, ?, ?, ?, 0.65, ?, ?, 'Stable', false, 'ACTIVE', NOW())
                """,
                EXPIRY,
                bias,
                strength,
                new BigDecimal(String.valueOf(compositeScore)).setScale(4, RoundingMode.HALF_UP),
                confidenceLabel,
                vixRegime);

        return jdbcTemplate.queryForObject(
                "SELECT id FROM agent1_signals ORDER BY created_at DESC LIMIT 1",
                UUID.class);
    }

    // =========================================================================
    // Console output helpers
    // =========================================================================

    private void printScenarioHeader(int num, String name, String inputs) {
        System.out.printf("""
                %n┌──────────────────────────────────────────────────────────────┐
                │  Scenario %d — %-49s│
                │  Inputs: %-53s│
                └──────────────────────────────────────────────────────────────┘%n""",
                num, name, inputs);
    }

    private void printTradeCard(TradeCardDto card, int scenarioNum) {
        String gatesSummary = card.gateResults().stream()
                .map(g -> g.gate() + ":" + (g.passed() ? "✓" : "✗")
                        + "(" + (g.value() != null ? g.value().setScale(2, RoundingMode.HALF_UP) : "—") + ")")
                .reduce((a, b) -> a + "  " + b)
                .orElse("—");

        System.out.printf("""
                  Trade ID         : %s
                  Status           : %s
                  Strategy         : %s  |  Direction: %s
                  Short Leg        : %s  strike=%-6d  LTP=₹%-8s  delta=%-8s  PoP=%s
                  Long  Leg        : %s  strike=%-6d  LTP=₹%s
                  Spread Width     : %d pts
                  Net Premium/Unit : ₹%s
                  Lots             : %d × %d units
                  Max Profit Total : ₹%s
                  Max Loss Total   : ₹%s  (theoretical)
                  RoC              : %s%%  (annualised: %s%%)
                  Gates            : %s%n""",
                card.tradeId(),
                card.status(),
                card.strategy(), card.spreadDirection(),
                card.shortLeg().optionType(), card.shortLeg().strike(),
                card.shortLeg().ltp(), card.shortLeg().delta(), card.shortLeg().pop(),
                card.longLeg().optionType(), card.longLeg().strike(),
                card.longLeg().ltp(),
                Math.abs(card.shortLeg().strike() - card.longLeg().strike()),
                card.netPremiumPerUnit(),
                card.lots(), card.lotSize(),
                card.maxProfitTotal(),
                card.theoreticalMaxLossTotal(),
                card.roc(), card.rocAnnualised(),
                gatesSummary);

        if (card.status() == TradeStatus.REJECTED) {
            String failedGates = card.gateResults().stream()
                    .filter(g -> !g.passed())
                    .map(g -> g.gate() + " [" + g.description() + " | actual="
                            + (g.value() != null ? g.value().setScale(4, RoundingMode.HALF_UP) : "—")
                            + " min=" + (g.threshold() != null ? g.threshold() : "—") + "]")
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("unknown");
            System.out.printf("  ⚠ REJECTED — %s%n", failedGates);
        } else {
            System.out.printf("  ✓ PENDING_CONFIRM — all gates passed%n");
        }
    }

    private void printMonitorConfig(MonitorConfigDto config) {
        System.out.printf("""
                  [Confirmed + Monitor Config]
                  Short Fill       : ₹%s  →  Long Fill: ₹%s
                  Actual Net Prem  : ₹%s/unit
                  Max Profit       : ₹%s  |  Max Loss: ₹%s
                  Slippage Alert   : %s  |  Amount: ₹%s
                  T1 Watch         : %s  |  T2 Readjust: %s  |  T3 Exit: %s%n""",
                config.shortLeg().ltp(), config.longLeg().ltp(),
                config.actualNetPremiumPerUnit(),
                config.maxProfitTotal(), config.actualMaxLossTotal(),
                config.slippageAlert(), config.slippageAmount(),
                config.thresholds().t1WatchNiftyLevel(),
                config.thresholds().t2ReadjustNiftyLevel(),
                config.thresholds().t3ExitNiftyLevel());
    }

    // =========================================================================
    // HTTP client
    // =========================================================================

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // =========================================================================
    // Expiry date
    // =========================================================================

    /**
     * Returns the nearest upcoming Tuesday (never today even if today is Tuesday,
     * to avoid running against an expiry that may already be past market close).
     */
    private static LocalDate nextTuesdayExpiry() {
        LocalDate today = LocalDate.now();
        int daysUntilTuesday = DayOfWeek.TUESDAY.getValue() - today.getDayOfWeek().getValue();
        if (daysUntilTuesday <= 0) daysUntilTuesday += 7;
        return today.plusDays(daysUntilTuesday);
    }
}
