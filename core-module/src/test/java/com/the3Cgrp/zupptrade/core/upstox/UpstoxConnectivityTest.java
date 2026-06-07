package com.the3Cgrp.zupptrade.core.upstox;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxMarketQuoteClient;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live connectivity tests — call real Upstox APIs with credentials from application-local.yml.
 *
 * HOW TO RUN:
 *   1. Fill in src/test/resources/application-local.yml with your credentials.
 *   2. Run: mvn test -pl core-module -Dgroups=integration -DJAVA_HOME="C:\Program Files\Java\jdk-21"
 *
 * These tests are excluded from the default Surefire run (tagged "integration").
 * They require a valid Upstox access token — paste a fresh one in application-local.yml daily.
 */
@Tag("integration")
@SpringBootTest(classes = UpstoxTestApplication.class)
@ActiveProfiles("local")
class UpstoxConnectivityTest {

    @Autowired private UpstoxProfileClient profileClient;
    @Autowired private UpstoxHistoricalDataClient historicalClient;
    @Autowired private UpstoxOptionChainClient optionChainClient;
    @Autowired private UpstoxMarketQuoteClient marketQuoteClient;

    private static final LocalDate EXPIRY = LocalDate.of(2026, 5, 27); // Next Tuesday Nifty expiry

    // -------------------------------------------------------------------------
    // Test 1 — user profile (simplest auth check)
    // -------------------------------------------------------------------------

    @Test
    void profileEndpoint_returnsUserDetails() {
        UpstoxUserProfile profile = profileClient.getProfile();

        System.out.println("=== Upstox User Profile ===");
        System.out.println("User ID   : " + profile.userId());
        System.out.println("User Name : " + profile.userName());
        System.out.println("Email     : " + profile.email());
        System.out.println("Broker    : " + profile.broker());
        System.out.println("Exchanges : " + profile.exchanges());

        assertThat(profile.userId()).isNotBlank();
        assertThat(profile.broker()).isNotBlank();
        System.out.println("✓ Auth OK — access token is valid");
    }

    // -------------------------------------------------------------------------
    // Test 2 — historical candles
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
        UpstoxHistoricalDataClient.UpstoxCandle latest = candles.get(0);
        assertThat(latest.close()).isGreaterThan(java.math.BigDecimal.ZERO);
        System.out.println("✓ Historical candles OK — " + candles.size() + " candles returned");
    }

    // -------------------------------------------------------------------------
    // Test 3 — option chain (raw rows)
    // -------------------------------------------------------------------------

    @Test
    void optionChain_returnsStrikeData() {
        List<UpstoxOptionChainRow> rows =
                optionChainClient.fetchRaw("NSE_INDEX|Nifty 50", EXPIRY);

        System.out.println("\n=== Nifty Option Chain — expiry " + EXPIRY + " ===");
        System.out.println("Total strikes : " + rows.size());
        if (!rows.isEmpty()) {
            UpstoxOptionChainRow first = rows.get(0);
            System.out.println("Spot price    : " + first.underlyingSpotPrice());
            System.out.println("Sample strike : " + first.strikePrice());
            if (first.callOptions() != null && first.callOptions().marketData() != null) {
                System.out.println("CE LTP        : " + first.callOptions().marketData().ltp());
                System.out.println("CE OI         : " + first.callOptions().marketData().oi());
            }
            if (first.putOptions() != null && first.putOptions().marketData() != null) {
                System.out.println("PE LTP        : " + first.putOptions().marketData().ltp());
                System.out.println("PE OI         : " + first.putOptions().marketData().oi());
            }
        }

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).underlyingSpotPrice()).isNotNull();
        System.out.println("✓ Option chain OK — " + rows.size() + " strikes returned");
    }

    // -------------------------------------------------------------------------
    // Test 4 — option chain summary (PCR, max pain, VIX)
    // -------------------------------------------------------------------------

    @Test
    void optionChainSummary_computesPcrAndMaxPain() {
        UpstoxOptionChainSummary summary = optionChainClient.fetch(EXPIRY);

        assertThat(summary)
                .as("Option chain summary is null — token likely expired. Check logs for 'upstox.token.expired'")
                .isNotNull();

        System.out.println("\n=== Option Chain Summary — expiry " + EXPIRY + " ===");
        System.out.println("Spot      : " + summary.spot());
        System.out.println("PCR       : " + summary.pcr());
        System.out.println("Max Pain  : " + summary.maxPain());
        System.out.println("VIX       : " + summary.vixLevel());
        System.out.println("Call OI Δ : " + summary.callOiChange());
        System.out.println("Put OI Δ  : " + summary.putOiChange());

        assertThat(summary.spot()).isNotNull().isGreaterThan(BigDecimal.ZERO);
        assertThat(summary.pcr()).isNotNull().isGreaterThan(BigDecimal.ZERO);
        assertThat(summary.maxPain()).isNotNull().isGreaterThan(0);
        // futuresPremium is null on weekends/holidays — not asserted, just printed
        System.out.println("✓ Option chain summary OK");
    }

    // -------------------------------------------------------------------------
    // Test 5 — Nifty futures LTP and premium calculation
    // -------------------------------------------------------------------------

    @Test
    void niftyFuturesPremium_fetchesLtpAndComputesPremium() {
        // Build the expected futures key for today using the same logic as the client
        String futuresKey = UpstoxOptionChainClient.buildNiftyFuturesKey(LocalDate.now());
        System.out.println("\n=== Nifty Futures Premium ===");
        System.out.println("Futures key (today) : " + futuresKey);

        BigDecimal futuresLtp = marketQuoteClient.fetchLtp(futuresKey);
        System.out.println("Futures LTP         : " + futuresLtp
                + (futuresLtp == null ? " (null — expected on weekends/holidays)" : ""));

        // Also fetch spot so we can compute and display the premium manually
        UpstoxOptionChainSummary summary = optionChainClient.fetch(EXPIRY);
        BigDecimal spot = summary != null ? summary.spot() : null;
        System.out.println("Nifty spot          : " + spot);

        if (futuresLtp != null && spot != null) {
            BigDecimal premium = futuresLtp.subtract(spot);
            System.out.println("Computed premium    : " + premium + " pts");

            String signal = premium.compareTo(new BigDecimal("20"))  > 0 ? "BULLISH (+1)" :
                            premium.compareTo(new BigDecimal("-20")) < 0 ? "BEARISH (-1)" : "NEUTRAL (0)";
            System.out.println("Tier 1A signal      : " + signal + " (threshold ±20 pts)");

            // Premium should be a small number relative to spot — sanity bounds ±500 pts
            assertThat(premium)
                    .as("Futures premium should be within ±500 pts of spot")
                    .isBetween(new BigDecimal("-500"), new BigDecimal("500"));

            // Note: futuresPremium is NOT on UpstoxOptionChainSummary (core record).
            // The full premium wiring (fetchLtp + subtract spot) lives in agent1's
            // UpstoxOptionChainClient wrapper. Run agent1 UpstoxConnectivityTest to
            // verify the end-to-end premium value in OptionChainSummary.
            System.out.println("✓ Futures LTP fetch OK — key=" + futuresKey + " ltp=" + futuresLtp + " premium=" + premium + " pts");
        } else {
            // Acceptable on weekends/holidays when real-time LTP is unavailable,
            // or when the access token has expired (check logs for upstox.token.expired)
            System.out.println("⚠ Futures LTP unavailable — markets closed (weekend/holiday) or token expired");
            System.out.println("  Check logs: 'upstox.token.expired' = stale token; no such log = market closed");
            System.out.println("  Re-run with a fresh token on a trading day to verify the premium value.");
        }
    }
}
