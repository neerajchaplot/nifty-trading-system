package com.the3Cgrp.zupptrade.agent4.calculator;

import com.the3Cgrp.zupptrade.agent4.domain.enums.AccuracyVerdict;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Derives Agent 1 accuracy from the pre-computed accuracy_verdict column
 * in v_agent4_signal_quality.
 *
 * Accuracy rate excludes NOT_MEASURED and NO_TRADE rows — only ACCURATE and WRONG
 * are in the denominator.
 *
 * Enhancement path (T-FUTURE / T48): replace the proxy with a price-based verdict
 * that compares Nifty spot at entry vs Nifty closing price on expiry date.
 */
public final class Agent1AccuracyCalculator {

    private Agent1AccuracyCalculator() {}

    /**
     * Computes outcome-based accuracy rate from a list of pre-computed verdict strings.
     * Input strings match AccuracyVerdict enum names: ACCURATE, WRONG, NOT_MEASURED, NO_TRADE.
     *
     * @return accuracy % as BigDecimal (0–100), or ZERO when no measurable verdicts exist.
     */
    public static BigDecimal accuracyRate(List<String> verdicts) {
        if (verdicts == null || verdicts.isEmpty()) return BigDecimal.ZERO;

        long accurate = verdicts.stream()
                .filter(v -> AccuracyVerdict.ACCURATE.name().equals(v))
                .count();
        long wrong = verdicts.stream()
                .filter(v -> AccuracyVerdict.WRONG.name().equals(v))
                .count();

        long measured = accurate + wrong;
        if (measured == 0) return BigDecimal.ZERO;

        return BigDecimal.valueOf(accurate)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(measured), 2, RoundingMode.HALF_UP);
    }

    /**
     * Segments a list of verdict strings by a grouping key and computes accuracy rate per group.
     * Used for accuracy by confidence (HIGH/MEDIUM/LOW) and accuracy by bias (BULLISH/BEARISH).
     *
     * @param rows         list of rows from the view; each row has groupKey and accuracy_verdict columns.
     * @param groupKey     column name to group by.
     * @return map of groupKey value → accuracy %.
     */
    public static java.util.Map<String, BigDecimal> accuracyByGroup(
            List<java.util.Map<String, Object>> rows, String groupKey) {

        java.util.Map<String, List<String>> grouped = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> row : rows) {
            String group   = String.valueOf(row.getOrDefault(groupKey, "UNKNOWN"));
            String verdict = String.valueOf(row.getOrDefault("accuracy_verdict", "NO_TRADE"));
            grouped.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(verdict);
        }

        java.util.Map<String, BigDecimal> result = new java.util.LinkedHashMap<>();
        grouped.forEach((group, verdictList) ->
                result.put(group, accuracyRate(verdictList)));
        return result;
    }
}
