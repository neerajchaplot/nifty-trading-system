package com.the3Cgrp.zupptrade.agent4.calculator;

import com.the3Cgrp.zupptrade.agent4.domain.enums.AccuracyVerdict;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Price-based Agent 1 signal-accuracy calculator (pure Java, no Spring/DB).
 *
 * <p>Accuracy is a property of the SIGNAL alone — did Nifty move from the signal's
 * scoring-time spot ({@code signal_spot}) to its expiry-day close ({@code expiry_close})
 * the way the signal's bias + strength promised. It is completely independent of whether
 * a trade was taken or what strategy was used.
 *
 * <p>Verdict rule (thresholds supplied from DB, never hardcoded here):
 * <ul>
 *   <li>BULLISH · EXTREME → net move ≥ +extremePoints</li>
 *   <li>BULLISH · MILD    → net move ≥ +mildPoints</li>
 *   <li>BEARISH · EXTREME → net move ≤ −extremePoints</li>
 *   <li>BEARISH · MILD    → net move ≤ −mildPoints</li>
 *   <li>WEAK (any bias) / NEUTRAL → |net move| ≤ neutralBandPoints (stayed in range)</li>
 * </ul>
 * Expiry not yet passed → {@link AccuracyVerdict#PENDING}; missing spot or close →
 * {@link AccuracyVerdict#NOT_MEASURED}. Both are excluded from the accuracy rate.
 *
 * TODO(PARTIAL): grade "direction right, magnitude short" as a distinct PARTIAL verdict.
 */
public final class Agent1AccuracyCalculator {

    private Agent1AccuracyCalculator() {}

    /** Point thresholds defining whether a signal's directional promise was met (from reference_data). */
    public record Thresholds(BigDecimal extremePoints, BigDecimal mildPoints, BigDecimal neutralBandPoints) {}

    // ── Per-signal verdict ────────────────────────────────────────────────────

    /**
     * Grades one signal from typed inputs. Strategy/trade play no role.
     *
     * @param today the reference "now" used to decide whether the expiry has resolved.
     */
    public static AccuracyVerdict verdict(String bias, String strength,
                                          BigDecimal signalSpot, BigDecimal expiryClose,
                                          LocalDate expiryDate, LocalDate today,
                                          Thresholds t) {

        if (expiryDate == null || today == null || expiryDate.isAfter(today)) {
            return AccuracyVerdict.PENDING;
        }
        if (signalSpot == null || expiryClose == null || t == null) {
            return AccuracyVerdict.NOT_MEASURED;
        }

        BigDecimal netMove = expiryClose.subtract(signalSpot);
        String s = strength == null ? "" : strength.trim().toUpperCase();
        String b = bias     == null ? "" : bias.trim().toUpperCase();

        // Bullish/Bearish-Weak is a range call (Agent 2 treats Weak bias as Neutral);
        // a NEUTRAL signal is always Weak. Both grade on staying inside the neutral band.
        if ("WEAK".equals(s) || "NEUTRAL".equals(b)) {
            return withinBand(netMove, t.neutralBandPoints()) ? AccuracyVerdict.ACCURATE : AccuracyVerdict.WRONG;
        }

        BigDecimal required = "EXTREME".equals(s) ? t.extremePoints() : t.mildPoints();
        return switch (b) {
            case "BULLISH" -> netMove.compareTo(required) >= 0
                    ? AccuracyVerdict.ACCURATE : AccuracyVerdict.WRONG;
            case "BEARISH" -> netMove.compareTo(required.negate()) <= 0
                    ? AccuracyVerdict.ACCURATE : AccuracyVerdict.WRONG;
            default -> AccuracyVerdict.NOT_MEASURED;   // unknown bias — cannot grade
        };
    }

    private static boolean withinBand(BigDecimal netMove, BigDecimal band) {
        if (band == null) return false;
        return netMove.abs().compareTo(band) <= 0;
    }

    /** Grades one signal from a {@code v_agent4_signal_quality} row. */
    public static AccuracyVerdict verdictForRow(Map<String, Object> row, LocalDate today, Thresholds t) {
        return verdict(
                asString(row.get("bias")),
                asString(row.get("strength")),
                PortfolioMetricsCalculator.toBd(row.get("signal_spot")),
                PortfolioMetricsCalculator.toBd(row.get("expiry_close")),
                asDate(row.get("expiry_date")),
                today, t);
    }

    // ── Aggregate accuracy ────────────────────────────────────────────────────

    /**
     * Accuracy % = ACCURATE ÷ (ACCURATE + WRONG) × 100.
     * Returns {@code null} (N/A) when nothing is measurable — a deliberate distinction from a
     * genuine 0%. PENDING and NOT_MEASURED never enter the denominator.
     */
    public static BigDecimal accuracyRate(List<Map<String, Object>> rows, LocalDate today, Thresholds t) {
        if (rows == null || rows.isEmpty()) return null;

        long accurate = 0, wrong = 0;
        for (Map<String, Object> row : rows) {
            AccuracyVerdict v = verdictForRow(row, today, t);
            if (v == AccuracyVerdict.ACCURATE) accurate++;
            else if (v == AccuracyVerdict.WRONG) wrong++;
        }

        long measured = accurate + wrong;
        if (measured == 0) return null;
        return BigDecimal.valueOf(accurate)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(measured), 2, RoundingMode.HALF_UP);
    }

    /**
     * Accuracy segmented by a row column (e.g. {@code confidence_label} or {@code bias}).
     * A group with nothing measurable maps to {@code null} (N/A).
     */
    public static Map<String, BigDecimal> accuracyByGroup(List<Map<String, Object>> rows, String groupKey,
                                                          LocalDate today, Thresholds t) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                String g = asString(row.getOrDefault(groupKey, "UNKNOWN"));
                grouped.computeIfAbsent(g, k -> new ArrayList<>()).add(row);
            }
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        grouped.forEach((g, groupRows) -> result.put(g, accuracyRate(groupRows, today, t)));
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static LocalDate asDate(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof LocalDate ld) return ld;
        return LocalDate.parse(v.toString());
    }
}
