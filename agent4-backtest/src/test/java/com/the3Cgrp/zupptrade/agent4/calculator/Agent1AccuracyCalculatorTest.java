package com.the3Cgrp.zupptrade.agent4.calculator;

import com.the3Cgrp.zupptrade.agent4.domain.enums.AccuracyVerdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Agent1AccuracyCalculatorTest {

    // ── accuracyRate ──────────────────────────────────────────────────────────

    @Test
    void nullListReturnsZero() {
        assertThat(Agent1AccuracyCalculator.accuracyRate(null))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void emptyListReturnsZero() {
        assertThat(Agent1AccuracyCalculator.accuracyRate(Collections.emptyList()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void allAccurateReturns100() {
        List<String> verdicts = List.of(
                AccuracyVerdict.ACCURATE.name(),
                AccuracyVerdict.ACCURATE.name(),
                AccuracyVerdict.ACCURATE.name()
        );
        assertThat(Agent1AccuracyCalculator.accuracyRate(verdicts))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void allWrongReturnsZero() {
        List<String> verdicts = List.of(
                AccuracyVerdict.WRONG.name(),
                AccuracyVerdict.WRONG.name()
        );
        assertThat(Agent1AccuracyCalculator.accuracyRate(verdicts))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void threeAccurateOneWrongIs75Percent() {
        List<String> verdicts = List.of(
                AccuracyVerdict.ACCURATE.name(),
                AccuracyVerdict.ACCURATE.name(),
                AccuracyVerdict.ACCURATE.name(),
                AccuracyVerdict.WRONG.name()
        );
        assertThat(Agent1AccuracyCalculator.accuracyRate(verdicts))
                .isEqualByComparingTo(new BigDecimal("75.00"));
    }

    @Test
    void notMeasuredExcludedFromDenominator() {
        // 2 ACCURATE, 5 NOT_MEASURED → denominator = 2 → 100%
        List<String> verdicts = List.of(
                AccuracyVerdict.ACCURATE.name(),
                AccuracyVerdict.ACCURATE.name(),
                AccuracyVerdict.NOT_MEASURED.name(),
                AccuracyVerdict.NOT_MEASURED.name(),
                AccuracyVerdict.NOT_MEASURED.name(),
                AccuracyVerdict.NOT_MEASURED.name(),
                AccuracyVerdict.NOT_MEASURED.name()
        );
        assertThat(Agent1AccuracyCalculator.accuracyRate(verdicts))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void mixWithNotMeasuredAndWrong() {
        // 2 ACCURATE, 1 WRONG, 5 NOT_MEASURED → 2/3 = 66.67%
        List<String> verdicts = List.of(
                AccuracyVerdict.ACCURATE.name(),
                AccuracyVerdict.ACCURATE.name(),
                AccuracyVerdict.WRONG.name(),
                AccuracyVerdict.NOT_MEASURED.name(),
                AccuracyVerdict.NOT_MEASURED.name(),
                AccuracyVerdict.NOT_MEASURED.name(),
                AccuracyVerdict.NOT_MEASURED.name(),
                AccuracyVerdict.NOT_MEASURED.name()
        );
        assertThat(Agent1AccuracyCalculator.accuracyRate(verdicts))
                .isEqualByComparingTo(new BigDecimal("66.67"));
    }

    @Test
    void onlyNotMeasuredReturnsZero() {
        List<String> verdicts = List.of(
                AccuracyVerdict.NOT_MEASURED.name(),
                AccuracyVerdict.NOT_MEASURED.name()
        );
        assertThat(Agent1AccuracyCalculator.accuracyRate(verdicts))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── accuracyByGroup ───────────────────────────────────────────────────────

    @Test
    void accuracyByGroupSegmentsByConfidence() {
        List<Map<String, Object>> rows = List.of(
                row("HIGH",   AccuracyVerdict.ACCURATE.name()),
                row("HIGH",   AccuracyVerdict.ACCURATE.name()),
                row("HIGH",   AccuracyVerdict.WRONG.name()),
                row("MEDIUM", AccuracyVerdict.ACCURATE.name()),
                row("LOW",    AccuracyVerdict.WRONG.name()),
                row("LOW",    AccuracyVerdict.NOT_MEASURED.name())
        );
        Map<String, BigDecimal> result =
                Agent1AccuracyCalculator.accuracyByGroup(rows, "confidence");

        // HIGH: 2/3 = 66.67%
        assertThat(result.get("HIGH")).isEqualByComparingTo(new BigDecimal("66.67"));
        // MEDIUM: 1/1 = 100%
        assertThat(result.get("MEDIUM")).isEqualByComparingTo(new BigDecimal("100.00"));
        // LOW: 0/1 measured = 0% (NOT_MEASURED excluded)
        assertThat(result.get("LOW")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void accuracyByGroupEmptyRowsReturnsEmptyMap() {
        Map<String, BigDecimal> result =
                Agent1AccuracyCalculator.accuracyByGroup(List.of(), "confidence");
        assertThat(result).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> row(String confidence, String verdict) {
        return Map.of("confidence", confidence, "accuracy_verdict", verdict);
    }
}
