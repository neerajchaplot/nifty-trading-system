package com.the3Cgrp.zupptrade.agent4.calculator;

import com.the3Cgrp.zupptrade.agent4.calculator.Agent1AccuracyCalculator.Thresholds;
import com.the3Cgrp.zupptrade.agent4.domain.enums.AccuracyVerdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Agent1AccuracyCalculatorTest {

    // Thresholds: EXTREME 200, MILD 100, neutral band ±100
    private static final Thresholds T =
            new Thresholds(new BigDecimal("200"), new BigDecimal("100"), new BigDecimal("100"));

    private static final LocalDate TODAY   = LocalDate.of(2026, 7, 22);
    private static final LocalDate RESOLVED = LocalDate.of(2026, 7, 21); // past expiry
    private static final LocalDate FUTURE  = LocalDate.of(2026, 7, 28);  // unresolved expiry

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private static AccuracyVerdict verdict(String bias, String strength, String spot, String close, LocalDate expiry) {
        return Agent1AccuracyCalculator.verdict(bias, strength,
                spot == null ? null : bd(spot),
                close == null ? null : bd(close),
                expiry, TODAY, T);
    }

    // ── BULLISH ───────────────────────────────────────────────────────────────

    @Test
    void bullishExtremeMeetsTarget() {
        // +200 exactly → ACCURATE
        assertThat(verdict("BULLISH", "EXTREME", "23400", "23600", RESOLVED)).isEqualTo(AccuracyVerdict.ACCURATE);
    }

    @Test
    void bullishExtremeShortOfTargetIsWrong() {
        // +160 < 200 → WRONG (directionally right, magnitude short; PARTIAL later)
        assertThat(verdict("BULLISH", "EXTREME", "23400", "23560", RESOLVED)).isEqualTo(AccuracyVerdict.WRONG);
    }

    @Test
    void bullishMildMeetsTarget() {
        assertThat(verdict("BULLISH", "MILD", "23400", "23530", RESOLVED)).isEqualTo(AccuracyVerdict.ACCURATE);
    }

    @Test
    void bullishMildDownMoveIsWrong() {
        assertThat(verdict("BULLISH", "MILD", "23400", "23350", RESOLVED)).isEqualTo(AccuracyVerdict.WRONG);
    }

    // ── BEARISH ───────────────────────────────────────────────────────────────

    @Test
    void bearishExtremeMeetsTarget() {
        // −250 ≤ −200 → ACCURATE
        assertThat(verdict("BEARISH", "EXTREME", "23400", "23150", RESOLVED)).isEqualTo(AccuracyVerdict.ACCURATE);
    }

    @Test
    void bearishMildMeetsTarget() {
        assertThat(verdict("BEARISH", "MILD", "23400", "23290", RESOLVED)).isEqualTo(AccuracyVerdict.ACCURATE);
    }

    @Test
    void bearishMildUpMoveIsWrong() {
        assertThat(verdict("BEARISH", "MILD", "23400", "23600", RESOLVED)).isEqualTo(AccuracyVerdict.WRONG);
    }

    // ── WEAK / NEUTRAL (range rule) ─────────────────────────────────────────────

    @Test
    void neutralWithinBandIsAccurate() {
        // |+60| ≤ 100 → ACCURATE
        assertThat(verdict("NEUTRAL", "WEAK", "23400", "23460", RESOLVED)).isEqualTo(AccuracyVerdict.ACCURATE);
    }

    @Test
    void neutralBreaksBandIsWrong() {
        assertThat(verdict("NEUTRAL", "WEAK", "23400", "23560", RESOLVED)).isEqualTo(AccuracyVerdict.WRONG);
    }

    @Test
    void bullishWeakIsGradedAsRangeNotDirection() {
        // Bullish-Weak → Agent 2 treats as Neutral → range rule. +60 within band → ACCURATE.
        assertThat(verdict("BULLISH", "WEAK", "23400", "23460", RESOLVED)).isEqualTo(AccuracyVerdict.ACCURATE);
        // +150 breaks the band even though it rose → WRONG (it promised a range).
        assertThat(verdict("BULLISH", "WEAK", "23400", "23550", RESOLVED)).isEqualTo(AccuracyVerdict.WRONG);
    }

    // ── PENDING / NOT_MEASURED ──────────────────────────────────────────────────

    @Test
    void futureExpiryIsPending() {
        assertThat(verdict("BULLISH", "EXTREME", "23400", "23600", FUTURE)).isEqualTo(AccuracyVerdict.PENDING);
    }

    @Test
    void nullExpiryIsPending() {
        assertThat(verdict("BULLISH", "EXTREME", "23400", "23600", null)).isEqualTo(AccuracyVerdict.PENDING);
    }

    @Test
    void missingSpotIsNotMeasured() {
        assertThat(verdict("BULLISH", "EXTREME", null, "23600", RESOLVED)).isEqualTo(AccuracyVerdict.NOT_MEASURED);
    }

    @Test
    void missingCloseIsNotMeasured() {
        assertThat(verdict("BULLISH", "EXTREME", "23400", null, RESOLVED)).isEqualTo(AccuracyVerdict.NOT_MEASURED);
    }

    @Test
    void unknownBiasIsNotMeasured() {
        assertThat(verdict("SIDEWAYS", "MILD", "23400", "23600", RESOLVED)).isEqualTo(AccuracyVerdict.NOT_MEASURED);
    }

    @Test
    void nullThresholdsIsNotMeasured() {
        assertThat(Agent1AccuracyCalculator.verdict("BULLISH", "MILD", bd("23400"), bd("23600"), RESOLVED, TODAY, null))
                .isEqualTo(AccuracyVerdict.NOT_MEASURED);
    }

    // ── accuracyRate (rows) ─────────────────────────────────────────────────────

    private static Map<String, Object> row(String bias, String strength, String spot, String close, LocalDate expiry) {
        Map<String, Object> m = new HashMap<>();
        m.put("bias", bias);
        m.put("strength", strength);
        m.put("signal_spot", spot == null ? null : bd(spot));
        m.put("expiry_close", close == null ? null : bd(close));
        m.put("expiry_date", expiry);
        return m;
    }

    @Test
    void accuracyRateNullWhenNothingMeasurable() {
        // all PENDING or NOT_MEASURED → N/A, not 0%
        List<Map<String, Object>> rows = List.of(
                row("BULLISH", "EXTREME", "23400", "23600", FUTURE),          // PENDING
                row("BULLISH", "EXTREME", null, "23600", RESOLVED),           // NOT_MEASURED
                row("BEARISH", "MILD", "23400", null, RESOLVED)               // NOT_MEASURED
        );
        assertThat(Agent1AccuracyCalculator.accuracyRate(rows, TODAY, T)).isNull();
    }

    @Test
    void accuracyRateEmptyOrNullRowsIsNull() {
        assertThat(Agent1AccuracyCalculator.accuracyRate(List.of(), TODAY, T)).isNull();
        assertThat(Agent1AccuracyCalculator.accuracyRate(null, TODAY, T)).isNull();
    }

    @Test
    void accuracyRateExcludesPendingAndNotMeasured() {
        // 2 ACCURATE, 1 WRONG, plus 1 PENDING + 1 NOT_MEASURED (excluded) → 2/3 = 66.67%
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("BULLISH", "MILD", "23400", "23520", RESOLVED));  // +120 ACCURATE
        rows.add(row("BEARISH", "EXTREME", "23400", "23150", RESOLVED)); // -250 ACCURATE
        rows.add(row("BULLISH", "MILD", "23400", "23350", RESOLVED));  // -50  WRONG
        rows.add(row("NEUTRAL", "WEAK", "23400", "23460", FUTURE));    // PENDING
        rows.add(row("BEARISH", "MILD", "23400", null, RESOLVED));     // NOT_MEASURED

        assertThat(Agent1AccuracyCalculator.accuracyRate(rows, TODAY, T))
                .isEqualByComparingTo(new BigDecimal("66.67"));
    }

    @Test
    void accuracyRateAllAccurateIs100() {
        List<Map<String, Object>> rows = List.of(
                row("BULLISH", "MILD", "23400", "23520", RESOLVED),
                row("NEUTRAL", "WEAK", "23400", "23440", RESOLVED));
        assertThat(Agent1AccuracyCalculator.accuracyRate(rows, TODAY, T))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // ── accuracyByGroup ─────────────────────────────────────────────────────────

    @Test
    void accuracyByGroupSegmentsAndNullsEmptyGroups() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // HIGH: 1 ACCURATE, 1 WRONG → 50%
        rows.add(withConfidence(row("BULLISH", "MILD", "23400", "23520", RESOLVED), "HIGH"));
        rows.add(withConfidence(row("BULLISH", "MILD", "23400", "23350", RESOLVED), "HIGH"));
        // MEDIUM: 1 ACCURATE → 100%
        rows.add(withConfidence(row("NEUTRAL", "WEAK", "23400", "23440", RESOLVED), "MEDIUM"));
        // LOW: only NOT_MEASURED → null (N/A)
        rows.add(withConfidence(row("BULLISH", "MILD", null, "23600", RESOLVED), "LOW"));

        Map<String, BigDecimal> result =
                Agent1AccuracyCalculator.accuracyByGroup(rows, "confidence_label", TODAY, T);

        assertThat(result.get("HIGH")).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(result.get("MEDIUM")).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(result.get("LOW")).isNull();
    }

    @Test
    void accuracyByGroupEmptyRowsIsEmptyMap() {
        assertThat(Agent1AccuracyCalculator.accuracyByGroup(List.of(), "confidence_label", TODAY, T)).isEmpty();
    }

    private static Map<String, Object> withConfidence(Map<String, Object> row, String label) {
        row.put("confidence_label", label);
        return row;
    }
}
