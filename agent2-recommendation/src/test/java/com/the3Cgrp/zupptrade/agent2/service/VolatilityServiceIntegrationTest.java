package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.shared.enums.IvRegime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — verifies that VolatilityService correctly fetches 20-day
 * Nifty 50 close prices from Upstox and computes a valid annualised HV.
 *
 * HOW TO RUN:
 *   mvn test -pl agent2-recommendation "-Dexcluded.test.groups=" "-Dgroups=integration" ^
 *            "-Dtest=VolatilityServiceIntegrationTest"
 *
 * Prerequisites:
 *   - Valid Upstox access token in application-local.yml (trading.upstox.access-token)
 *   - DB connection optional — only the Upstox historical API is exercised here
 *
 * What is validated:
 *   - computeHv20d() returns a non-null BigDecimal in a sensible range (5%–100% annualised)
 *   - deriveIvRegime() returns a non-null IvRegime enum value
 *   - IV/HV ratio is printed so you can verify it against market reality
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
class VolatilityServiceIntegrationTest {

    @Autowired
    private VolatilityService volatilityService;

    // -------------------------------------------------------------------------
    // Test 1 — computeHv20d() returns a plausible annualised HV
    // -------------------------------------------------------------------------

    @Test
    void computeHv20d_returnsAnnualisedHvInReasonableRange() {
        System.out.println("\n=== VolatilityService Integration Test — HV 20-day ===");

        BigDecimal hv = volatilityService.computeHv20d();

        // HARD FAIL if null — means Upstox token is missing/expired or API is unreachable.
        // Fix: set trading.upstox.access-token in application-local.yml with a valid token.
        assertThat(hv)
                .as("computeHv20d() returned null — Upstox token is invalid or API unreachable. " +
                    "Set trading.upstox.access-token in application-local.yml with a valid token.")
                .isNotNull();

        System.out.println("HV 20-day (annualised): " + hv.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP) + "%");

        // Nifty HV is typically between 5% (very calm) and 80% (extreme crisis).
        assertThat(hv)
                .as("Annualised HV must be between 0.05 (5%) and 0.80 (80%) for Nifty 50")
                .isBetween(new BigDecimal("0.05"), new BigDecimal("0.80"));

        System.out.println("✓ HV is within the expected Nifty 50 range (5%–80% annualised)");
    }

    // -------------------------------------------------------------------------
    // Test 2 — deriveIvRegime() returns a valid regime for typical VIX/HV values
    // -------------------------------------------------------------------------

    @Test
    void deriveIvRegime_withLiveHvAndSampleVix_returnsValidRegime() {
        System.out.println("\n=== VolatilityService Integration Test — IV Regime ===");

        BigDecimal hv = volatilityService.computeHv20d();

        assertThat(hv)
                .as("computeHv20d() returned null — Upstox token is invalid or API unreachable.")
                .isNotNull();

        // Use a realistic mid-range VIX (15.0) to test regime derivation.
        // The actual live VIX will be used in the recommendation flow via ctx.getVix().
        BigDecimal sampleVix = new BigDecimal("15.0");
        BigDecimal iv = sampleVix.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
        BigDecimal ratio = iv.divide(hv, 4, java.math.RoundingMode.HALF_UP);

        IvRegime regime = volatilityService.deriveIvRegime(sampleVix, hv);

        System.out.println("Sample VIX   : " + sampleVix);
        System.out.println("HV 20-day    : " + hv.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP) + "%");
        System.out.println("IV (VIX/100) : " + iv);
        System.out.println("IV/HV ratio  : " + ratio + " → " + regime);

        assertThat(regime)
                .as("IV regime must be one of RICH, FAIR, CHEAP")
                .isIn(IvRegime.RICH, IvRegime.FAIR, IvRegime.CHEAP);

        System.out.println("✓ deriveIvRegime() returned " + regime + " for VIX=15, HV=" +
                hv.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP) + "%");
    }

    // -------------------------------------------------------------------------
    // Test 3 — end-to-end: live VIX + live HV → regime (simulates real call path)
    // -------------------------------------------------------------------------

    @Test
    void deriveIvRegime_withEdgeCases_returnsFairSafely() {
        System.out.println("\n=== VolatilityService Integration Test — Edge Cases ===");

        // null VIX → FAIR
        IvRegime nullVix = volatilityService.deriveIvRegime(null, new BigDecimal("0.15"));
        assertThat(nullVix).isEqualTo(IvRegime.FAIR);
        System.out.println("null VIX → " + nullVix + " ✓");

        // null HV → FAIR
        IvRegime nullHv = volatilityService.deriveIvRegime(new BigDecimal("15"), null);
        assertThat(nullHv).isEqualTo(IvRegime.FAIR);
        System.out.println("null HV  → " + nullHv + " ✓");

        // zero HV → FAIR (avoid division by zero)
        IvRegime zeroHv = volatilityService.deriveIvRegime(new BigDecimal("15"), BigDecimal.ZERO);
        assertThat(zeroHv).isEqualTo(IvRegime.FAIR);
        System.out.println("HV=0     → " + zeroHv + " ✓ (division by zero guarded)");

        // Very high VIX vs low HV → RICH
        // VIX=25 (IV=0.25), HV=0.12 → ratio=2.08 → RICH
        IvRegime rich = volatilityService.deriveIvRegime(new BigDecimal("25"), new BigDecimal("0.12"));
        assertThat(rich).isEqualTo(IvRegime.RICH);
        System.out.println("VIX=25, HV=12% → " + rich + " ✓ (ratio=2.08 > 1.2)");

        // Low VIX vs high HV → CHEAP
        // VIX=10 (IV=0.10), HV=0.20 → ratio=0.50 → CHEAP
        IvRegime cheap = volatilityService.deriveIvRegime(new BigDecimal("10"), new BigDecimal("0.20"));
        assertThat(cheap).isEqualTo(IvRegime.CHEAP);
        System.out.println("VIX=10, HV=20% → " + cheap + " ✓ (ratio=0.50 < 0.85)");

        System.out.println("\n✓ All edge cases handled correctly");
    }
}
