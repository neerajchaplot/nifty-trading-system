package com.the3Cgrp.zupptrade.agent4.calculator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DrawdownCalculatorTest {

    @Test
    void nullListReturnsZeros() {
        DrawdownCalculator.DrawdownResult r = DrawdownCalculator.compute(null);
        assertThat(r.maxDrawdown()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.consecutiveLossesInWorstRun()).isZero();
        assertThat(r.totalLossTrades()).isZero();
    }

    @Test
    void emptyListReturnsZeros() {
        DrawdownCalculator.DrawdownResult r = DrawdownCalculator.compute(Collections.emptyList());
        assertThat(r.maxDrawdown()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.consecutiveLossesInWorstRun()).isZero();
        assertThat(r.totalLossTrades()).isZero();
    }

    @Test
    void allWinsProduceZeroDrawdown() {
        List<BigDecimal> pnl = List.of(bd("5000"), bd("3000"), bd("8000"));
        DrawdownCalculator.DrawdownResult r = DrawdownCalculator.compute(pnl);
        assertThat(r.maxDrawdown()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.consecutiveLossesInWorstRun()).isZero();
        assertThat(r.totalLossTrades()).isZero();
    }

    @Test
    void singleLossDrawdownEqualsLossAmount() {
        DrawdownCalculator.DrawdownResult r = DrawdownCalculator.compute(List.of(bd("-10000")));
        assertThat(r.maxDrawdown()).isEqualByComparingTo(bd("10000"));
        assertThat(r.consecutiveLossesInWorstRun()).isEqualTo(1);
        assertThat(r.totalLossTrades()).isEqualTo(1);
    }

    @Test
    void typicalMixWinLossWin() {
        // win 5k, lose 8k, win 3k → peak=5k, trough=-3k, drawdown=8k
        List<BigDecimal> pnl = List.of(bd("5000"), bd("-8000"), bd("3000"));
        DrawdownCalculator.DrawdownResult r = DrawdownCalculator.compute(pnl);
        assertThat(r.maxDrawdown()).isEqualByComparingTo(bd("8000"));
        assertThat(r.consecutiveLossesInWorstRun()).isEqualTo(1);
        assertThat(r.totalLossTrades()).isEqualTo(1);
    }

    @Test
    void consecutiveLossesTrackedCorrectly() {
        // L L W L L L → worst run = 3
        List<BigDecimal> pnl = List.of(
                bd("-1000"), bd("-2000"),
                bd("500"),
                bd("-1000"), bd("-1000"), bd("-1000")
        );
        DrawdownCalculator.DrawdownResult r = DrawdownCalculator.compute(pnl);
        assertThat(r.consecutiveLossesInWorstRun()).isEqualTo(3);
        assertThat(r.totalLossTrades()).isEqualTo(5);
    }

    @Test
    void nullEntriesInListAreSkipped() {
        List<BigDecimal> pnl = Arrays.asList(bd("5000"), null, bd("-2000"), null, bd("1000"));
        DrawdownCalculator.DrawdownResult r = DrawdownCalculator.compute(pnl);
        assertThat(r.maxDrawdown()).isEqualByComparingTo(bd("2000"));
        assertThat(r.totalLossTrades()).isEqualTo(1);
    }

    @Test
    void drawdownFromPeakNotFromStartWhenStartIsLoss() {
        // starts negative: -2k (no peak yet), then wins 10k, then loses 4k
        // peak = 8k, trough = 4k, drawdown = 4k
        List<BigDecimal> pnl = List.of(bd("-2000"), bd("10000"), bd("-4000"));
        DrawdownCalculator.DrawdownResult r = DrawdownCalculator.compute(pnl);
        assertThat(r.maxDrawdown()).isEqualByComparingTo(bd("4000"));
    }

    @Test
    void recoveryAfterDrawdownResetsToNewPeak() {
        // 3k, -2k (dd=2k), recover 4k (running=5k, new peak), -1k (dd=1k) → max dd = 2k
        List<BigDecimal> pnl = List.of(bd("3000"), bd("-2000"), bd("4000"), bd("-1000"));
        DrawdownCalculator.DrawdownResult r = DrawdownCalculator.compute(pnl);
        assertThat(r.maxDrawdown()).isEqualByComparingTo(bd("2000"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
