package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.agent1.client.GiftNiftyClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — verifies that GiftNiftyClient fetches a live GIFT Nifty LTP
 * from Upstox v3 API (GLOBAL_INDEX|SGX NIFTY) and correctly computes the
 * Tier 3 premium signal against Nifty 50 previous close.
 *
 * HOW TO RUN:
 *   mvn test -pl agent1-market_analyst "-Dexcluded.test.groups=" "-Dgroups=integration" ^
 *            "-Dtest=GiftNiftyConnectivityTest"
 *
 * Prerequisites:
 *   - Fresh Upstox token in DB (run upstox-auth first)
 *   - GIFT Nifty is live — trades Mon–Fri ~6:30 AM to 11:30 PM IST
 *   - Returns null outside trading hours (test will warn, not fail)
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
class GiftNiftyConnectivityTest {

    @Autowired
    private GiftNiftyClient giftNiftyClient;

    @Autowired
    private UpstoxHistoricalDataClient historicalDataClient;

    // -------------------------------------------------------------------------
    // Test 1 — raw LTP fetch
    // -------------------------------------------------------------------------

    @Test
    void giftNifty_fetchLtp_returnsLiveValue() {
        System.out.println("\n=== GIFT Nifty Live LTP (Upstox v3 — GLOBAL_INDEX|SGX NIFTY) ===");

        BigDecimal ltp = giftNiftyClient.fetchLtp();

        if (ltp == null) {
            System.out.println("⚠ LTP null — GIFT Nifty not trading right now or token expired.");
            System.out.println("  Check logs for 'upstox.token.expired'.");
            System.out.println("  GIFT Nifty trades Mon–Fri ~6:30 AM to 11:30 PM IST.");
            return;
        }

        System.out.println("GIFT Nifty LTP : " + ltp);

        assertThat(ltp)
            .as("GIFT Nifty LTP must be a realistic Nifty-range value")
            .isGreaterThan(new BigDecimal("10000"))
            .isLessThan(new BigDecimal("100000"));

        System.out.println("✓ GIFT Nifty LTP fetch OK — Upstox v3 GLOBAL_INDEX feed confirmed");
    }

    // -------------------------------------------------------------------------
    // Test 2 — Tier 3 premium signal (GIFT Nifty vs Nifty 50 prev close)
    // -------------------------------------------------------------------------

    @Test
    void giftNifty_tier3Signal_premiumVsNiftyPrevClose() {
        System.out.println("\n=== GIFT Nifty Tier 3 Signal — Premium vs Nifty 50 Prev Close ===");

        BigDecimal giftNiftyLtp = giftNiftyClient.fetchLtp();

        // Nifty 50 previous close from historical candles (candle[0] = most recent session)
        List<UpstoxHistoricalDataClient.UpstoxCandle> candles =
                historicalDataClient.fetchNiftyDailyCandles(2);

        if (giftNiftyLtp == null) {
            System.out.println("⚠ GIFT Nifty LTP null — cannot compute premium.");
            System.out.println("  Expected outside trading hours or on token expiry.");
            return;
        }

        assertThat(candles)
            .as("Need at least 1 historical candle for Nifty prev close")
            .isNotEmpty();

        BigDecimal niftyPrevClose = candles.get(0).close();
        BigDecimal premium = giftNiftyLtp.subtract(niftyPrevClose)
                                         .setScale(2, RoundingMode.HALF_UP);

        System.out.println("GIFT Nifty LTP    : " + giftNiftyLtp);
        System.out.println("Nifty 50 Prev Close: " + niftyPrevClose);
        System.out.println("Premium (GN - N50) : " + premium + " pts");
        System.out.println();
        System.out.println("--- Tier 3 Signal (threshold ±50 pts per CLAUDE.md) ---");
        System.out.println("Gift Nifty        : " + tier3Signal(premium));

        // Sanity: premium should be within ±500 pts of Nifty
        assertThat(premium)
            .as("GIFT Nifty premium sanity bound ±500 pts")
            .isBetween(new BigDecimal("-500"), new BigDecimal("500"));

        System.out.println("\n✓ Gift Nifty Tier 3 signal computed successfully");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static String tier3Signal(BigDecimal premium) {
        BigDecimal threshold = new BigDecimal("50");
        if (premium.compareTo(threshold) > 0)           return "BULLISH (+1)  premium=" + premium + " pts";
        if (premium.compareTo(threshold.negate()) < 0)  return "BEARISH (-1)  premium=" + premium + " pts";
        return                                                  "NEUTRAL (0)   premium=" + premium + " pts";
    }
}
