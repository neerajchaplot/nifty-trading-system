package com.the3Cgrp.zupptrade.agent4.calculator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioMetricsCalculatorTest {

    // ── winRate ───────────────────────────────────────────────────────────────

    @Test
    void winRateWithZeroTotalReturnsZero() {
        assertThat(PortfolioMetricsCalculator.winRate(0, 0))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void winRatePerfect() {
        assertThat(PortfolioMetricsCalculator.winRate(5, 5))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void winRateThreeOfFive() {
        assertThat(PortfolioMetricsCalculator.winRate(3, 5))
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void winRateZeroWins() {
        assertThat(PortfolioMetricsCalculator.winRate(0, 4))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── avgRocAchieved ────────────────────────────────────────────────────────

    @Test
    void avgRocAchievedEmptyListReturnsZero() {
        assertThat(PortfolioMetricsCalculator.avgRocAchieved(Collections.emptyList()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void avgRocAchievedSkipsNullAndZeroRoc() {
        List<Map<String, Object>> rows = List.of(
                Map.of("roc_achieved_pct", new BigDecimal("2.50")),
                Map.of("roc_achieved_pct", new BigDecimal("0")),
                Map.of("roc_achieved_pct", new BigDecimal("1.50"))
        );
        // zero roc is skipped, so avg = (2.50 + 1.50) / 2 = 2.00
        assertThat(PortfolioMetricsCalculator.avgRocAchieved(rows))
                .isEqualByComparingTo(new BigDecimal("2.0000"));
    }

    @Test
    void avgRocAchievedTypicalCase() {
        List<Map<String, Object>> rows = List.of(
                Map.of("roc_achieved_pct", new BigDecimal("1.00")),
                Map.of("roc_achieved_pct", new BigDecimal("3.00"))
        );
        assertThat(PortfolioMetricsCalculator.avgRocAchieved(rows))
                .isEqualByComparingTo(new BigDecimal("2.0000"));
    }

    // ── rocCaptureRatio ───────────────────────────────────────────────────────

    @Test
    void rocCaptureRatioZeroTheoreticalReturnsZero() {
        assertThat(PortfolioMetricsCalculator.rocCaptureRatio(bd("1.5"), BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rocCaptureRatioNullTheoreticalReturnsZero() {
        assertThat(PortfolioMetricsCalculator.rocCaptureRatio(bd("1.5"), null))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rocCaptureRatioTypical() {
        // actual = 1.5, theoretical = 2.0 → capture = 75%
        assertThat(PortfolioMetricsCalculator.rocCaptureRatio(bd("1.5"), bd("2.0")))
                .isEqualByComparingTo(new BigDecimal("75.00"));
    }

    @Test
    void rocCaptureRatioAbove100WhenActualExceedsTheoretical() {
        // actual = 3.0, theoretical = 2.0 → 150%
        assertThat(PortfolioMetricsCalculator.rocCaptureRatio(bd("3.0"), bd("2.0")))
                .isEqualByComparingTo(new BigDecimal("150.00"));
    }

    // ── strategyMix ───────────────────────────────────────────────────────────

    @Test
    void strategyMixCountsCorrectly() {
        List<Map<String, Object>> rows = List.of(
                Map.of("strategy", "BullPutSpread"),
                Map.of("strategy", "BullPutSpread"),
                Map.of("strategy", "BearCallSpread"),
                Map.of("strategy", "IronCondor")
        );
        Map<String, Long> mix = PortfolioMetricsCalculator.strategyMix(rows);
        assertThat(mix).containsEntry("BullPutSpread", 2L)
                       .containsEntry("BearCallSpread", 1L)
                       .containsEntry("IronCondor", 1L);
    }

    @Test
    void strategyMixEmptyReturnsEmptyMap() {
        assertThat(PortfolioMetricsCalculator.strategyMix(Collections.emptyList())).isEmpty();
    }

    // ── winRateByGroup ────────────────────────────────────────────────────────

    @Test
    void winRateByGroupGroupsByVixRegime() {
        List<Map<String, Object>> rows = List.of(
                row("NORMAL", 3L, 2L),  // 2/3 = 66.67%
                row("HIGH",   2L, 2L),  // 2/2 = 100.00%
                row("LOW",    1L, 0L)   // 0/1 = 0%
        );
        Map<String, BigDecimal> result =
                PortfolioMetricsCalculator.winRateByGroup(rows, "entry_vix_regime");

        assertThat(result.get("NORMAL")).isEqualByComparingTo(new BigDecimal("66.67"));
        assertThat(result.get("HIGH")).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(result.get("LOW")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void winRateByGroupEmptyRowsReturnsEmptyMap() {
        assertThat(PortfolioMetricsCalculator
                .winRateByGroup(Collections.emptyList(), "entry_vix_regime")).isEmpty();
    }

    // ── adjustmentRecoveryRate ────────────────────────────────────────────────

    @Test
    void adjustmentRecoveryRateNoAdjustedTradesReturnsZero() {
        List<Map<String, Object>> rows = List.of(
                Map.of("adjustment_count", 0L, "actual_pnl", bd("5000")),
                Map.of("adjustment_count", 0L, "actual_pnl", bd("-2000"))
        );
        assertThat(PortfolioMetricsCalculator.adjustmentRecoveryRate(rows))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void adjustmentRecoveryRateTypical() {
        // 3 adjusted trades: 2 ended in profit, 1 in loss → 66.67%
        List<Map<String, Object>> rows = List.of(
                Map.of("adjustment_count", 1L, "actual_pnl", bd("3000")),
                Map.of("adjustment_count", 2L, "actual_pnl", bd("1500")),
                Map.of("adjustment_count", 1L, "actual_pnl", bd("-4000")),
                Map.of("adjustment_count", 0L, "actual_pnl", bd("500"))  // not adjusted — ignored
        );
        assertThat(PortfolioMetricsCalculator.adjustmentRecoveryRate(rows))
                .isEqualByComparingTo(new BigDecimal("66.67"));
    }

    @Test
    void adjustmentRecoveryRateAllRecovered() {
        List<Map<String, Object>> rows = List.of(
                Map.of("adjustment_count", 1L, "actual_pnl", bd("1000")),
                Map.of("adjustment_count", 1L, "actual_pnl", bd("2000"))
        );
        assertThat(PortfolioMetricsCalculator.adjustmentRecoveryRate(rows))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private static Map<String, Object> row(String vixRegime, long count, long wins) {
        return Map.of("entry_vix_regime", vixRegime, "trade_count", count, "win_count", wins);
    }
}
