package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.agent1.client.UpstoxFiiDiiClient;
import com.the3Cgrp.zupptrade.agent1.domain.entity.FiiDiiSnapshotEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiData;
import com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiTrend;
import com.the3Cgrp.zupptrade.agent1.repository.FiiDiiSnapshotRepository;
import com.the3Cgrp.zupptrade.agent1.service.FiiDiiService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — verifies that FiiDiiService persists daily snapshots
 * to fii_dii_snapshots and computes a 5-day trend from DB.
 *
 * HOW TO RUN:
 *   mvn test -pl agent1-market_analyst "-Dexcluded.test.groups=" "-Dgroups=integration" ^
 *            "-Dtest=FiiDiiPersistenceTest"
 *
 * Prerequisites:
 *   - Valid Upstox token in Neon DB (run upstox-auth first)
 *   - TOKEN_ENCRYPTION_KEY env var set
 *   - Flyway V3 migration applied (fii_dii_snapshots table exists)
 *
 * Backtesting validation:
 *   - Test 3 queries by date range — the same query Agent 4 will use for backtesting.
 *   - If snapshots accumulate over multiple runs, Test 3 verifies they are queryable
 *     by trading_date range and segment.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FiiDiiPersistenceTest {

    @Autowired
    private FiiDiiService fiiDiiService;

    @Autowired
    private FiiDiiSnapshotRepository repository;

    // -------------------------------------------------------------------------
    // Test 1 — fetchAndPersist() returns valid FiiDiiData with trend
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void fetchAndPersist_returnsValidData_withTrend() {
        System.out.println("\n======================================================");
        System.out.println(" FII/DII Persistence Test 1 — fetchAndPersist()");
        System.out.println("======================================================");

        FiiDiiData data = fiiDiiService.fetchAndPersist();

        if (data == null) {
            System.out.println("⚠  fetchAndPersist() returned null.");
            System.out.println("   Expected on weekends/holidays or if Upstox token expired.");
            System.out.println("   Check logs for 'upstox.token.expired'.");
            return;
        }

        System.out.println("FII Net Futures : " + data.fiiNetFutures() + " ₹Cr");
        System.out.println("FII Net Options : " + data.fiiNetOptions() + " ₹Cr");
        System.out.println("DII Net Cash    : " + data.diiNet() + " ₹Cr");
        System.out.println("FII Long Ratio  : " + data.fiiLongRatio());

        FiiDiiTrend trend = data.futuresTrend();
        assertThat(trend).as("Trend must always be populated (UNAVAILABLE on first run)").isNotNull();
        System.out.println("\n--- 5-Day Trend ---");
        System.out.println("Direction       : " + trend.direction());
        System.out.println("Avg Net Flow 5d : " + trend.avgNetFlow5d() + " ₹Cr");
        System.out.println("Days Positive   : " + trend.daysPositive() + " / " + trend.snapshotCount());
        System.out.println("Days Negative   : " + trend.daysNegative() + " / " + trend.snapshotCount());

        assertThat(trend.direction())
                .as("Trend direction must be one of the known values")
                .isIn("IMPROVING", "DETERIORATING", "FLAT", "UNAVAILABLE");

        // At least one of the net flow fields must be populated
        assertThat(data.fiiNetFutures() != null
                || data.fiiNetOptions() != null
                || data.diiNet() != null)
                .as("At least one net flow field must be non-null")
                .isTrue();

        System.out.println("\n✓ FiiDiiService.fetchAndPersist() returned valid data with trend");
    }

    // -------------------------------------------------------------------------
    // Test 2 — snapshots are persisted to DB for all three segments
    // -------------------------------------------------------------------------

    @Test
    @Order(2)
    void snapshots_persistedToDb_forAllSegments() {
        System.out.println("\n======================================================");
        System.out.println(" FII/DII Persistence Test 2 — DB rows verification");
        System.out.println("======================================================");

        LocalDate oneWeekAgo = LocalDate.now().minusDays(7);

        List<FiiDiiSnapshotEntity> futures = repository
                .findBySegmentAndTradingDateBetweenOrderByTradingDateDesc(
                        UpstoxFiiDiiClient.DATA_TYPE_FII_FUTURES, oneWeekAgo, LocalDate.now());
        List<FiiDiiSnapshotEntity> options = repository
                .findBySegmentAndTradingDateBetweenOrderByTradingDateDesc(
                        UpstoxFiiDiiClient.DATA_TYPE_FII_OPTIONS, oneWeekAgo, LocalDate.now());
        List<FiiDiiSnapshotEntity> dii = repository
                .findBySegmentAndTradingDateBetweenOrderByTradingDateDesc(
                        UpstoxFiiDiiClient.DATA_TYPE_DII_CASH, oneWeekAgo, LocalDate.now());

        System.out.println("INDEX_FUTURES rows (last 7 days) : " + futures.size());
        System.out.println("INDEX_OPTIONS rows (last 7 days) : " + options.size());
        System.out.println("DII CASH rows (last 7 days)      : " + dii.size());

        futures.forEach(s -> System.out.printf(
                "  FUTURES  %s  net=%-12s  longRatio=%s%n",
                s.getTradingDate(), s.getNetFlow(), s.getLongRatio()));
        options.forEach(s -> System.out.printf(
                "  OPTIONS  %s  net=%s%n",
                s.getTradingDate(), s.getNetFlow()));
        dii.forEach(s -> System.out.printf(
                "  DII CASH %s  net=%s%n",
                s.getTradingDate(), s.getNetFlow()));

        // At least one segment must have rows if today is a trading day
        long totalRows = futures.size() + options.size() + dii.size();
        if (totalRows == 0) {
            System.out.println("⚠  No rows found — acceptable on weekends/holidays if Test 1 also returned null.");
        } else {
            assertThat(futures).as("INDEX_FUTURES snapshots must be persisted").isNotEmpty();
            assertThat(options).as("INDEX_OPTIONS snapshots must be persisted").isNotEmpty();
            assertThat(dii).as("DII CASH snapshots must be persisted").isNotEmpty();

            // Verify idempotency: running again should not create duplicates
            fiiDiiService.fetchAndPersist();
            long afterSecondRun = repository
                    .findBySegmentAndTradingDateBetweenOrderByTradingDateDesc(
                            UpstoxFiiDiiClient.DATA_TYPE_FII_FUTURES, oneWeekAgo, LocalDate.now())
                    .size();
            assertThat(afterSecondRun)
                    .as("Second fetchAndPersist() must not create duplicate rows (idempotent)")
                    .isEqualTo(futures.size());

            System.out.println("\n✓ Snapshots persisted — " + totalRows + " rows across all segments");
            System.out.println("✓ Idempotency confirmed — second run added 0 duplicate rows");
        }
    }

    // -------------------------------------------------------------------------
    // Test 3 — date-range query works for backtesting use case
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    void dateRangeQuery_supportsBacktestingLookup() {
        System.out.println("\n======================================================");
        System.out.println(" FII/DII Persistence Test 3 — Backtest range query");
        System.out.println("======================================================");

        // Agent 4 (backtest) will query a date range to replay inputs
        LocalDate to   = LocalDate.now();
        LocalDate from = to.minusDays(30);  // last 30 calendar days

        List<FiiDiiSnapshotEntity> rows = repository
                .findBySegmentAndTradingDateBetweenOrderByTradingDateDesc(
                        UpstoxFiiDiiClient.DATA_TYPE_FII_FUTURES, from, to);

        System.out.println("INDEX_FUTURES rows (last 30 days): " + rows.size());
        rows.forEach(s -> System.out.printf(
                "  %s  net=%-12s  longRatio=%s  posOrNeg=%s%n",
                s.getTradingDate(),
                s.getNetFlow(),
                s.getLongRatio(),
                s.getNetFlow() == null ? "?"
                        : s.getNetFlow().compareTo(java.math.BigDecimal.ZERO) >= 0 ? "POSITIVE" : "NEGATIVE"));

        if (!rows.isEmpty()) {
            // Verify rows are ordered newest-first (required for trend calculations)
            for (int i = 0; i < rows.size() - 1; i++) {
                assertThat(rows.get(i).getTradingDate())
                        .as("Rows must be ordered newest-first for backtesting replay")
                        .isAfterOrEqualTo(rows.get(i + 1).getTradingDate());
            }
            // Verify net_flow is stored (not null) — essential for trend and backtest scoring
            rows.stream()
                    .filter(r -> r.getBuyAmount() != null && r.getSellAmount() != null)
                    .forEach(r -> assertThat(r.getNetFlow())
                            .as("net_flow must be computed and stored for trading_date=" + r.getTradingDate())
                            .isNotNull());

            System.out.println("\n✓ Date-range query works — " + rows.size()
                    + " INDEX_FUTURES rows available for backtesting");
            System.out.println("✓ Rows ordered newest-first — confirmed for trend/backtest replay");
        } else {
            System.out.println("⚠  No rows yet — run test on a trading day to accumulate history.");
        }
    }
}
