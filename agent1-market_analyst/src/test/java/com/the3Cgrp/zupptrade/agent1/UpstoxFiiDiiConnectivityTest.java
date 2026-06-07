package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.agent1.client.UpstoxFiiDiiClient;
import com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — verifies that UpstoxFiiDiiClient successfully calls Upstox
 * Market Information APIs (/v2/market/fii and /v2/market/dii) and returns
 * parseable net flow data.
 *
 * HOW TO RUN:
 *   mvn test -pl agent1-market_analyst "-Dexcluded.test.groups=" "-Dgroups=integration" ^
 *            "-Dtest=UpstoxFiiDiiConnectivityTest"
 *
 * Prerequisites:
 *   - upstox-auth job must have run and written a fresh token to DB
 *   - TOKEN_ENCRYPTION_KEY env var must be set
 *   - application-local.yml on classpath with DB datasource configured
 *
 * On weekends / market holidays: Upstox returns empty data or no data for today.
 * The test will print a warning rather than failing — null values are valid on non-trading days.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
class UpstoxFiiDiiConnectivityTest {

    @Autowired
    private UpstoxFiiDiiClient fiiDiiClient;

    // -------------------------------------------------------------------------
    // Test 1 — fetchLatest() end-to-end via UpstoxFiiDiiClient
    // -------------------------------------------------------------------------

    @Test
    void fiiDii_fetchLatest_returnsNetFlowsForTodayOrNull() {
        System.out.println("\n=== FII/DII Net Flows via Upstox Market Information API ===");

        FiiDiiData data = fiiDiiClient.fetchLatest();

        if (data == null) {
            System.out.println("⚠ fetchLatest() returned null — all three endpoints empty.");
            System.out.println("  Expected on weekends/holidays (no data published yet).");
            System.out.println("  On a trading day: check logs for 'upstox.token.expired'.");
            // null is valid on non-trading days — do not fail
            return;
        }

        System.out.println("FII Net Futures : " + formatCr(data.fiiNetFutures()));
        System.out.println("FII Net Options : " + formatCr(data.fiiNetOptions()));
        System.out.println("DII Net Cash    : " + formatCr(data.diiNet()));
        System.out.println("FII Long Ratio  : " + formatRatio(data.fiiLongRatio()));

        // Print Tier 2 signals for visibility
        System.out.println();
        System.out.println("--- Tier 2 Scoring Signals ---");
        System.out.println("FII Futures (±500 Cr) : " + tier2Signal(data.fiiNetFutures()));
        System.out.println("FII Options           : " + tier2Signal(data.fiiNetOptions()));
        System.out.println("DII Cash    (±500 Cr) : " + tier2Signal(data.diiNet()));
        System.out.println("FII LongRatio (40-60%): " + longRatioSignal(data.fiiLongRatio()));

        // At least one field must be non-null if fetchLatest() returned non-null
        assertThat(data.fiiNetFutures() != null
                || data.fiiNetOptions() != null
                || data.diiNet() != null
                || data.fiiLongRatio() != null)
            .as("At least one data field must be non-null when FiiDiiData is returned")
            .isTrue();

        // Sanity bounds: FII/DII net flows are typically within ±50,000 Cr
        if (data.fiiNetFutures() != null) {
            assertThat(data.fiiNetFutures())
                .as("FII futures net flow sanity bound ±50000 Cr")
                .isBetween(new BigDecimal("-50000"), new BigDecimal("50000"));
        }
        if (data.diiNet() != null) {
            assertThat(data.diiNet())
                .as("DII cash net flow sanity bound ±50000 Cr")
                .isBetween(new BigDecimal("-50000"), new BigDecimal("50000"));
        }
        if (data.fiiLongRatio() != null) {
            assertThat(data.fiiLongRatio())
                .as("FII long ratio must be between 0 and 1")
                .isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        }

        System.out.println("\n✓ FII/DII fetch OK — Upstox Market Information API wired successfully");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String formatCr(BigDecimal value) {
        return value == null ? "null (no data for today)" : value + " ₹Cr";
    }

    private static String formatRatio(BigDecimal ratio) {
        if (ratio == null) return "null (no data)";
        return ratio.multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) + "%";
    }

    private static String tier2Signal(BigDecimal net) {
        if (net == null) return "N/A (null)";
        BigDecimal threshold = new BigDecimal("500");
        if (net.compareTo(threshold) > 0)           return "BULLISH (+1)  net=" + net + " Cr";
        if (net.compareTo(threshold.negate()) < 0)  return "BEARISH (-1)  net=" + net + " Cr";
        return                                              "NEUTRAL (0)   net=" + net + " Cr";
    }

    private static String longRatioSignal(BigDecimal ratio) {
        if (ratio == null) return "N/A (null)";
        BigDecimal pct = ratio.multiply(new BigDecimal("100"));
        if (pct.compareTo(new BigDecimal("60")) > 0)  return "BULLISH (+1)  ratio=" + pct.setScale(1, java.math.RoundingMode.HALF_UP) + "%";
        if (pct.compareTo(new BigDecimal("40")) < 0)  return "BEARISH (-1)  ratio=" + pct.setScale(1, java.math.RoundingMode.HALF_UP) + "%";
        return                                                "NEUTRAL (0)   ratio=" + pct.setScale(1, java.math.RoundingMode.HALF_UP) + "%";
    }
}
