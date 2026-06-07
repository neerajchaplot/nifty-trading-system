package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.agent1.client.UpstoxOptionChainClient.OptionChainSummary;
import com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenHolder;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxOptionChainClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxProfileClient;
import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxUserProfile;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainRow;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainSummary;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 integration test — verifies that:
 *   1. The Upstox access token is loaded automatically from the api_tokens DB table
 *      (written by upstox-auth module, decrypted using TOKEN_ENCRYPTION_KEY)
 *   2. The token is valid and accepted by the Upstox API
 *   3. Option chain and historical candle calls return real market data
 *
 * HOW TO RUN (from repo root):
 *   mvn test -pl agent1-market_analyst -Dgroups=integration
 *
 * Prerequisites:
 *   - upstox-auth job must have run at least once and written a fresh token to DB
 *   - TOKEN_ENCRYPTION_KEY env var set in the current terminal session
 *   - application-local.yml must be on classpath (src/main/resources — gitignored)
 *
 * These tests are EXCLUDED from the default Maven Surefire run (tagged "integration").
 * They call live Upstox APIs — do not run in CI.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
class UpstoxConnectivityTest {

    @Autowired private UpstoxTokenHolder      tokenHolder;
    @Autowired private UpstoxProfileClient    profileClient;
    @Autowired private UpstoxHistoricalDataClient historicalClient;
    @Autowired private UpstoxOptionChainClient optionChainClient;                          // core client
    @Autowired private com.the3Cgrp.zupptrade.agent1.client.UpstoxOptionChainClient agent1OptionChainClient; // agent1 wrapper

    // -------------------------------------------------------------------------
    // Test 1 — token loaded from DB (core goal of Phase 1)
    // -------------------------------------------------------------------------

    @Test
    void token_isLoadedFromDatabase() {
        String token = tokenHolder.getToken();

        System.out.println("\n=== Token Load Check ===");
        System.out.println("Token present : " + (token != null && !token.isBlank()));
        System.out.println("Token length  : " + (token != null ? token.length() : 0));

        assertThat(token)
            .as("Token must be loaded from api_tokens table — run upstox-auth first")
            .isNotBlank()
            .doesNotContain("PASTE_FRESH")
            .hasSizeGreaterThan(50);

        System.out.println("✓ Token loaded from DB — ApiTokenDbLoader working correctly");
    }

    // -------------------------------------------------------------------------
    // Test 2 — profile endpoint (simplest auth check)
    // -------------------------------------------------------------------------

    @Test
    void profileEndpoint_returnsUserDetails() {
        UpstoxUserProfile profile = profileClient.getProfile();

        System.out.println("\n=== Upstox User Profile ===");
        System.out.println("User ID   : " + profile.userId());
        System.out.println("User Name : " + profile.userName());
        System.out.println("Email     : " + profile.email());
        System.out.println("Broker    : " + profile.broker());
        System.out.println("Exchanges : " + profile.exchanges());

        assertThat(profile.userId()).isNotBlank();
        assertThat(profile.broker()).isNotBlank();
        System.out.println("✓ Auth OK — access token is valid and accepted by Upstox API");
    }

    // -------------------------------------------------------------------------
    // Test 3 — historical candles (feeds Tier 1A/1B EMA + RSI scoring)
    // -------------------------------------------------------------------------

    @Test
    void historicalCandles_returnsNiftyOhlcData() {
        List<UpstoxHistoricalDataClient.UpstoxCandle> candles =
                historicalClient.fetchNiftyDailyCandles(10);

        System.out.println("\n=== Nifty 50 Daily Candles (last 10 trading days) ===");
        candles.stream().limit(5).forEach(c ->
                System.out.printf("  %s  O=%.2f  H=%.2f  L=%.2f  C=%.2f  Vol=%d%n",
                        c.date(), c.open(), c.high(), c.low(), c.close(), c.volume()));

        assertThat(candles).isNotEmpty();
        assertThat(candles.get(0).close()).isGreaterThan(java.math.BigDecimal.ZERO);
        System.out.println("✓ Historical candles OK — " + candles.size() + " candles returned");
    }

    // -------------------------------------------------------------------------
    // Test 4 — option chain summary (PCR, max pain, VIX — feeds Tier 1A/3 scoring)
    // -------------------------------------------------------------------------

    @Test
    void optionChainSummary_computesPcrAndMaxPain() {
        LocalDate expiry = nextTuesdayExpiry();
        UpstoxOptionChainSummary summary = optionChainClient.fetch(expiry);

        assertThat(summary)
                .as("Option chain summary is null — token likely expired. " +
                    "Set TOKEN_ENCRYPTION_KEY env var and re-run upstox-auth to refresh.")
                .isNotNull();

        System.out.println("\n=== Option Chain Summary — expiry " + expiry + " ===");
        System.out.println("Spot      : " + summary.spot());
        System.out.println("PCR       : " + summary.pcr());
        System.out.println("Max Pain  : " + summary.maxPain());
        System.out.println("VIX       : " + summary.vixLevel());
        System.out.println("Call OI Δ : " + summary.callOiChange());
        System.out.println("Put OI Δ  : " + summary.putOiChange());

        assertThat(summary.spot()).isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat(summary.pcr()).isGreaterThan(java.math.BigDecimal.ZERO);
        System.out.println("✓ Option chain summary OK — spot=" + summary.spot() + " PCR=" + summary.pcr());
    }

    // -------------------------------------------------------------------------
    // Test 5 — raw option chain rows (strike-level data for Agent 2 scoring)
    // -------------------------------------------------------------------------

    @Test
    void optionChain_returnsStrikeRows() {
        LocalDate expiry = nextTuesdayExpiry();
        List<UpstoxOptionChainRow> rows =
                optionChainClient.fetchRaw("NSE_INDEX|Nifty 50", expiry);

        System.out.println("\n=== Nifty Option Chain Rows — expiry " + expiry + " ===");
        System.out.println("Total strikes : " + rows.size());
        if (!rows.isEmpty()) {
            UpstoxOptionChainRow first = rows.get(0);
            System.out.println("Spot price    : " + first.underlyingSpotPrice());
            System.out.println("First strike  : " + first.strikePrice());
        }

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).underlyingSpotPrice()).isNotNull().isGreaterThan(java.math.BigDecimal.ZERO);
        System.out.println("✓ Option chain rows OK — " + rows.size() + " strikes returned");
    }

    // -------------------------------------------------------------------------
    // Test 6 — futures premium (agent1 wrapper fetches futures LTP and computes premium)
    // -------------------------------------------------------------------------

    @Test
    void futuresPremium_isFetchedAndComputedByAgent1Wrapper() {
        LocalDate expiry = nextTuesdayExpiry();
        String futuresKey = UpstoxOptionChainClient.buildNiftyFuturesKey(LocalDate.now());

        System.out.println("\n=== Nifty Futures Premium (agent1 wrapper) ===");
        System.out.println("Futures key (today) : " + futuresKey);
        System.out.println("Expiry used         : " + expiry);

        OptionChainSummary summary = agent1OptionChainClient.fetch(expiry);

        assertThat(summary).as("Option chain summary must not be null — check token validity").isNotNull();

        System.out.println("Spot                : " + summary.spot());
        System.out.println("Futures premium     : " + summary.futuresPremium()
                + (summary.futuresPremium() == null ? " (null — expected on weekends/holidays)" : " pts"));

        if (summary.futuresPremium() != null) {
            String signal = summary.futuresPremium().compareTo(new BigDecimal("20"))  > 0 ? "BULLISH (+1)" :
                            summary.futuresPremium().compareTo(new BigDecimal("-20")) < 0 ? "BEARISH (-1)" : "NEUTRAL (0)";
            System.out.println("Tier 1A signal      : " + signal + "  (threshold ±20 pts per CLAUDE.md)");

            assertThat(summary.futuresPremium())
                    .as("Futures premium should be within ±500 pts of spot — sanity check")
                    .isBetween(new BigDecimal("-500"), new BigDecimal("500"));

            System.out.println("✓ Futures premium OK — premium=" + summary.futuresPremium() + " pts");
        } else {
            // Null is valid on weekends/holidays — real-time LTP endpoint returns nothing
            System.out.println("⚠ Futures premium null — expected on weekends/holidays.");
            System.out.println("  If this is a trading day, check logs for 'upstox.token.expired'.");
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /** Returns the next Tuesday (NSE weekly Nifty expiry day). */
    private static LocalDate nextTuesdayExpiry() {
        LocalDate today = LocalDate.now();
        int daysUntilTuesday = (DayOfWeek.TUESDAY.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        return daysUntilTuesday == 0 ? today : today.plusDays(daysUntilTuesday);
    }
}
