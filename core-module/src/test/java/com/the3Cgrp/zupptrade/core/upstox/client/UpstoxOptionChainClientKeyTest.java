package com.the3Cgrp.zupptrade.core.upstox.client;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UpstoxOptionChainClient#buildNiftyFuturesKey(LocalDate)}.
 *
 * Verifies:
 *   - Normal mid-month date → current month contract
 *   - On expiry Thursday itself → still current month (contract valid until close)
 *   - One day after expiry Thursday → rolls to next month
 *   - December → rolls to January of next year (year boundary)
 *
 * No Spring context needed — buildNiftyFuturesKey is package-private static.
 *
 * Run with: mvn test -pl core-module (no integration tag — included in default suite)
 */
class UpstoxOptionChainClientKeyTest {

    /**
     * 24 May 2026 — mid-month, well before last Thursday (28 May 2026).
     * Expected contract: NIFTY26MAYFUT
     */
    @Test
    void midMonth_returnCurrentMonthKey() {
        String key = UpstoxOptionChainClient.buildNiftyFuturesKey(LocalDate.of(2026, 5, 24));
        assertThat(key).isEqualTo("NSE_FO|NIFTY26MAYFUT");
    }

    /**
     * 28 May 2026 — the last Thursday of May (expiry day).
     * Contract is still valid until market close, so no rollover yet.
     * Expected contract: NIFTY26MAYFUT
     */
    @Test
    void expiryThursdayItself_returnCurrentMonthKey() {
        String key = UpstoxOptionChainClient.buildNiftyFuturesKey(LocalDate.of(2026, 5, 28));
        assertThat(key).isEqualTo("NSE_FO|NIFTY26MAYFUT");
    }

    /**
     * 29 May 2026 — one day after expiry Thursday.
     * May contract has expired; front month is now June.
     * Expected contract: NIFTY26JUNFUT
     */
    @Test
    void dayAfterExpiry_rollsToNextMonthKey() {
        String key = UpstoxOptionChainClient.buildNiftyFuturesKey(LocalDate.of(2026, 5, 29));
        assertThat(key).isEqualTo("NSE_FO|NIFTY26JUNFUT");
    }

    /**
     * 1 June 2026 — start of June, well before June expiry (25 June 2026).
     * Expected contract: NIFTY26JUNFUT
     */
    @Test
    void firstOfMonth_returnsCurrentMonthKey() {
        String key = UpstoxOptionChainClient.buildNiftyFuturesKey(LocalDate.of(2026, 6, 1));
        assertThat(key).isEqualTo("NSE_FO|NIFTY26JUNFUT");
    }

    /**
     * 31 December 2026 — last Thursday of December 2026 is 31 Dec (verify).
     * If today IS the last Thursday, still use December contract.
     * Expected contract: NIFTY26DECFUT
     *
     * Last Thursday of Dec 2026: Dec 31 is a Thursday → expiry = 31 Dec.
     */
    @Test
    void decemberExpiryDay_returnsDecemberKey() {
        // Verify assumption: 31 Dec 2026 is indeed a Thursday
        assertThat(LocalDate.of(2026, 12, 31).getDayOfWeek())
                .as("31 Dec 2026 should be a Thursday")
                .isEqualTo(java.time.DayOfWeek.THURSDAY);

        String key = UpstoxOptionChainClient.buildNiftyFuturesKey(LocalDate.of(2026, 12, 31));
        assertThat(key).isEqualTo("NSE_FO|NIFTY26DECFUT");
    }

    /**
     * 1 January 2027 — day after December expiry.
     * Rolls to January 2027 — year boundary test.
     * Expected contract: NIFTY27JANFUT
     */
    @Test
    void yearBoundary_rollsToNextYearJanuaryKey() {
        String key = UpstoxOptionChainClient.buildNiftyFuturesKey(LocalDate.of(2027, 1, 1));
        assertThat(key).isEqualTo("NSE_FO|NIFTY27JANFUT");
    }
}
