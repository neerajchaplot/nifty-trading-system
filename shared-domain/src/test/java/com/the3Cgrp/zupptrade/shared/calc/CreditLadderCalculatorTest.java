package com.the3Cgrp.zupptrade.shared.calc;

import com.the3Cgrp.zupptrade.shared.calc.CreditLadderCalculator.Ladder;
import com.the3Cgrp.zupptrade.shared.calc.CreditLadderCalculator.TargetPops;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exhaustive matrix for the credit T1/T2/T3 ladder.
 *
 * Scenarios covered (see each @DisplayName):
 *   1.  Baseline 80% entry → target PoPs exactly 70/64/57 and golden Nifty levels (PE).
 *   2.  Entry-PoP scaling — 70% (override) and 90% entries rescale the whole ladder.
 *   3.  Override problem fixed — at 70% entry, T1 target (61.25%) is strictly below entry (room exists).
 *   4.  DTE compression — as DTE 5→1, T1 tightens toward the strike (gamma), floor takes over at DTE 1.
 *   5.  VIX/IV widening — as IV 15→30%, T1 widens away from the strike (monotonic).
 *   6.  Static floor takeover — low IV / near expiry pins all three rungs to ±125 / ±100 / ±75.
 *   7.  T3 never inside ±75 — floor invariant holds for every case.
 *   8.  Ordering guaranteed — T1>T2>T3 (PE) / T1<T2<T3 (CE) in every case, incl. near expiry.
 *   9.  Bear Call (CE) mirror — levels sit below the short strike, floors ±125/±100/±75 below.
 *   10. Iron Condor — PE side and CE side computed independently off their own strikes.
 *   11. Dynamic recompute — same fixed target PoPs give tighter levels at lower DTE.
 *   12. Degenerate (DTE=0 / IV=0) — falls back to pure distance floors, never throws.
 *   13. Printed DTE and VIX tables for visual review.
 */
class CreditLadderCalculatorTest {

    private static final BigDecimal R = new BigDecimal("0.065");
    private static final BigDecimal IV = new BigDecimal("0.195");
    private static final int PE_STRIKE = 23500;
    private static final int CE_STRIKE = 24500;

    private static Ladder pe(BigDecimal entryPop, BigDecimal iv, int dte) {
        return CreditLadderCalculator.compute(PE_STRIKE, OptionType.PE, entryPop, iv, dte, R);
    }

    private static Ladder ce(BigDecimal entryPop, BigDecimal iv, int dte) {
        return CreditLadderCalculator.compute(CE_STRIKE, OptionType.CE, entryPop, iv, dte, R);
    }

    // ── 1. Baseline 80% entry ────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. 80% entry → target PoPs 70/64/57 and golden Nifty levels (PE)")
    void baseline80() {
        Ladder l = pe(new BigDecimal("0.80"), IV, 3);

        // Target PoPs are exact fractions of entry PoP
        assertThat(l.t1Pop()).isEqualByComparingTo("0.7000");
        assertThat(l.t2Pop()).isEqualByComparingTo("0.6400");
        assertThat(l.t3Pop()).isEqualByComparingTo("0.5700");

        // Golden inverted levels (PoP inversion), T3 pinned by the +75 floor (57% would be ~23565)
        assertThat(l.t1Nifty().doubleValue()).isCloseTo(23710, within(2.0)); // +210
        assertThat(l.t2Nifty().doubleValue()).isCloseTo(23641, within(2.0)); // +141
        assertThat(l.t3Nifty()).isEqualByComparingTo(new BigDecimal("23575")); // +75 floor
    }

    // ── 2 & 3. Entry-PoP scaling and the override fix ─────────────────────────────────

    @Test
    @DisplayName("2. Entry PoP scaling — 70% and 90% entries rescale target PoPs")
    void entryScaling() {
        TargetPops at70 = CreditLadderCalculator.targetPops(new BigDecimal("0.70"));
        assertThat(at70.t1()).isEqualByComparingTo("0.6125"); // 0.875 × 0.70
        assertThat(at70.t2()).isEqualByComparingTo("0.5600"); // 0.80  × 0.70
        assertThat(at70.t3()).isEqualByComparingTo("0.4988"); // 0.7125 × 0.70 (≈0.49875)

        TargetPops at90 = CreditLadderCalculator.targetPops(new BigDecimal("0.90"));
        assertThat(at90.t1()).isEqualByComparingTo("0.7875");
        assertThat(at90.t2()).isEqualByComparingTo("0.7200");
        assertThat(at90.t3()).isEqualByComparingTo("0.6413"); // 0.7125 × 0.90 = 0.641250 → 0.6413
    }

    @Test
    @DisplayName("3. Override fix — at a 70% entry, T1 target is BELOW entry (real downside room)")
    void overrideLeavesRoom() {
        BigDecimal entry = new BigDecimal("0.70");
        TargetPops t = CreditLadderCalculator.targetPops(entry);
        // The exact bug the user raised: fixed 70% would put T1 AT entry. Fractional keeps it below.
        assertThat(t.t1()).isLessThan(entry);
        assertThat(t.t2()).isLessThan(t.t1());
        assertThat(t.t3()).isLessThan(t.t2());
    }

    // ── 4. DTE compression (gamma) ────────────────────────────────────────────────────

    @Test
    @DisplayName("4. DTE compression — T1 tightens toward strike as DTE 5→1, floor binds at DTE 1")
    void dteCompression() {
        double d5 = dist(pe(new BigDecimal("0.80"), IV, 5).t1Nifty());
        double d3 = dist(pe(new BigDecimal("0.80"), IV, 3).t1Nifty());
        double d2 = dist(pe(new BigDecimal("0.80"), IV, 2).t1Nifty());
        double d1 = dist(pe(new BigDecimal("0.80"), IV, 1).t1Nifty());

        assertThat(d5).isGreaterThan(d3);
        assertThat(d3).isGreaterThan(d2);
        assertThat(d2).isGreaterThanOrEqualTo(d1);
        // By DTE 1 the 70% contour is inside +125, so the T1 floor takes over.
        assertThat(d1).isEqualTo(125.0);
    }

    // ── 5. VIX / IV widening ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("5. VIX/IV widening — T1 widens away from strike as IV 15→30% (monotonic)")
    void ivWidening() {
        double v15  = dist(pe(new BigDecimal("0.80"), new BigDecimal("0.15"),  3).t1Nifty());
        double v195 = dist(pe(new BigDecimal("0.80"), new BigDecimal("0.195"), 3).t1Nifty());
        double v25  = dist(pe(new BigDecimal("0.80"), new BigDecimal("0.25"),  3).t1Nifty());
        double v30  = dist(pe(new BigDecimal("0.80"), new BigDecimal("0.30"),  3).t1Nifty());

        assertThat(v15).isLessThan(v195);
        assertThat(v195).isLessThan(v25);
        assertThat(v25).isLessThan(v30);
        assertThat(v15).isGreaterThan(125.0); // all above the floor → PoP is binding
    }

    // ── 6. Static floor takeover ──────────────────────────────────────────────────────

    @Test
    @DisplayName("6. Floor takeover — low IV / near expiry pins T1/T2/T3 to +125/+100/+75")
    void floorTakeover() {
        Ladder l = pe(new BigDecimal("0.80"), new BigDecimal("0.08"), 1);
        assertThat(l.t1Nifty()).isEqualByComparingTo(new BigDecimal("23625")); // +125
        assertThat(l.t2Nifty()).isEqualByComparingTo(new BigDecimal("23600")); // +100
        assertThat(l.t3Nifty()).isEqualByComparingTo(new BigDecimal("23575")); // +75
    }

    // ── 7. T3 floor invariant ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("7. T3 is never closer than 75 pts to the short strike (any IV/DTE/entry)")
    void t3FloorInvariant() {
        for (String pop : new String[]{"0.70", "0.80", "0.90"}) {
            for (String iv : new String[]{"0.08", "0.15", "0.25", "0.40"}) {
                for (int dte : new int[]{1, 2, 3, 5, 7}) {
                    Ladder l = pe(new BigDecimal(pop), new BigDecimal(iv), dte);
                    assertThat(dist(l.t3Nifty()))
                            .as("T3 distance for pop=%s iv=%s dte=%d", pop, iv, dte)
                            .isGreaterThanOrEqualTo(75.0);
                }
            }
        }
    }

    // ── 8. Ordering guaranteed ────────────────────────────────────────────────────────

    @Test
    @DisplayName("8. Ordering T1>T2>T3 (PE) / T1<T2<T3 (CE) holds for every case incl. near expiry")
    void orderingGuaranteed() {
        for (String iv : new String[]{"0.08", "0.15", "0.195", "0.30"}) {
            for (int dte : new int[]{1, 2, 3, 5}) {
                Ladder pe = pe(new BigDecimal("0.80"), new BigDecimal(iv), dte);
                assertThat(pe.t1Nifty()).as("PE t1>t2 iv=%s dte=%d", iv, dte).isGreaterThan(pe.t2Nifty());
                assertThat(pe.t2Nifty()).as("PE t2>t3 iv=%s dte=%d", iv, dte).isGreaterThan(pe.t3Nifty());

                Ladder ce = ce(new BigDecimal("0.80"), new BigDecimal(iv), dte);
                assertThat(ce.t1Nifty()).as("CE t1<t2 iv=%s dte=%d", iv, dte).isLessThan(ce.t2Nifty());
                assertThat(ce.t2Nifty()).as("CE t2<t3 iv=%s dte=%d", iv, dte).isLessThan(ce.t3Nifty());
            }
        }
    }

    // ── 9. Bear Call (CE) mirror ──────────────────────────────────────────────────────

    @Test
    @DisplayName("9. Bear Call (CE) — levels sit below the short strike, floors 125/100/75 below")
    void bearCallMirror() {
        Ladder l = ce(new BigDecimal("0.80"), IV, 3);
        assertThat(l.t1Nifty().intValue()).isLessThan(CE_STRIKE);
        assertThat(l.t2Nifty().intValue()).isLessThan(CE_STRIKE);
        assertThat(l.t3Nifty().intValue()).isLessThan(CE_STRIKE);
        double t3DistBelowStrike = Math.abs(l.t3Nifty().subtract(BigDecimal.valueOf(CE_STRIKE)).doubleValue());
        assertThat(t3DistBelowStrike).isGreaterThanOrEqualTo(75.0);

        // Near-expiry / low IV pins to the floors below the strike
        Ladder floored = ce(new BigDecimal("0.80"), new BigDecimal("0.08"), 1);
        assertThat(floored.t1Nifty()).isEqualByComparingTo(new BigDecimal("24375")); // -125
        assertThat(floored.t2Nifty()).isEqualByComparingTo(new BigDecimal("24400")); // -100
        assertThat(floored.t3Nifty()).isEqualByComparingTo(new BigDecimal("24425")); // -75
    }

    // ── 10. Iron Condor — both sides independent ──────────────────────────────────────

    @Test
    @DisplayName("10. Iron Condor — PE side above its strike, CE side below its strike")
    void ironCondorBothSides() {
        BigDecimal entry = new BigDecimal("0.85");
        Ladder put  = CreditLadderCalculator.compute(23500, OptionType.PE, entry, IV, 3, R);
        Ladder call = CreditLadderCalculator.compute(24500, OptionType.CE, entry, IV, 3, R);

        assertThat(put.t3Nifty().intValue()).isGreaterThanOrEqualTo(23500 + 75);
        assertThat(call.t3Nifty().intValue()).isLessThanOrEqualTo(24500 - 75);
        // Same entry PoP → symmetric target PoPs on both sides
        assertThat(put.t1Pop()).isEqualByComparingTo(call.t1Pop());
    }

    // ── 11. Dynamic recompute ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("11. Recompute with fixed target PoPs — lower DTE gives tighter levels")
    void dynamicRecompute() {
        TargetPops fixed = CreditLadderCalculator.targetPops(new BigDecimal("0.85"));
        Ladder atDte3 = CreditLadderCalculator.levels(PE_STRIKE, OptionType.PE, fixed, IV, 3, R);
        Ladder atDte1 = CreditLadderCalculator.levels(PE_STRIKE, OptionType.PE, fixed, IV, 1, R);
        assertThat(atDte1.t1Nifty()).isLessThan(atDte3.t1Nifty());
        // Target PoPs unchanged across cycles
        assertThat(atDte1.t1Pop()).isEqualByComparingTo(atDte3.t1Pop());
    }

    // ── 12. Degenerate DTE=0 / IV=0 ───────────────────────────────────────────────────

    @Test
    @DisplayName("12. Degenerate (DTE=0 or IV=0) → pure distance floors, never throws")
    void degenerateFallsBackToFloors() {
        Ladder dte0 = pe(new BigDecimal("0.80"), IV, 0);
        assertThat(dte0.t1Nifty()).isEqualByComparingTo(new BigDecimal("23625"));
        assertThat(dte0.t2Nifty()).isEqualByComparingTo(new BigDecimal("23600"));
        assertThat(dte0.t3Nifty()).isEqualByComparingTo(new BigDecimal("23575"));

        Ladder iv0 = pe(new BigDecimal("0.80"), BigDecimal.ZERO, 3);
        assertThat(iv0.t3Nifty()).isEqualByComparingTo(new BigDecimal("23575"));
    }

    // ── 13. Printed tables for visual review ──────────────────────────────────────────

    @Test
    @DisplayName("13. Print DTE and VIX sweeps (PE short 23500, entry 80%)")
    void printSweeps() {
        StringBuilder sb = new StringBuilder("\n=== DTE sweep (PE 23500, IV 19.5%, entry 80%) ===\n");
        sb.append(String.format("%-5s %-8s %-8s %-8s%n", "DTE", "T1", "T2", "T3"));
        for (int dte : new int[]{7, 5, 3, 2, 1}) {
            Ladder l = pe(new BigDecimal("0.80"), IV, dte);
            sb.append(String.format("%-5d %-8s %-8s %-8s%n", dte, l.t1Nifty(), l.t2Nifty(), l.t3Nifty()));
        }
        sb.append("\n=== VIX/IV sweep (PE 23500, DTE 3, entry 80%) ===\n");
        sb.append(String.format("%-6s %-8s %-8s %-8s%n", "IV", "T1", "T2", "T3"));
        for (String iv : new String[]{"0.12", "0.15", "0.195", "0.25", "0.30", "0.40"}) {
            Ladder l = pe(new BigDecimal("0.80"), new BigDecimal(iv), 3);
            sb.append(String.format("%-6s %-8s %-8s %-8s%n", iv, l.t1Nifty(), l.t2Nifty(), l.t3Nifty()));
        }
        sb.append("\n=== Entry-PoP sweep (PE 23500, IV 19.5%, DTE 3) ===\n");
        sb.append(String.format("%-8s %-10s %-10s %-10s%n", "entry", "T1(pop)", "T2(pop)", "T3(pop)"));
        for (String p : new String[]{"0.70", "0.80", "0.85", "0.90"}) {
            Ladder l = pe(new BigDecimal(p), IV, 3);
            sb.append(String.format("%-8s %-4s(%.2f) %-4s(%.2f) %-4s(%.2f)%n", p,
                    l.t1Nifty(), l.t1Pop(), l.t2Nifty(), l.t2Pop(), l.t3Nifty(), l.t3Pop()));
        }
        System.out.println(sb);
        assertThat(sb.length()).isPositive();
    }

    private static double dist(BigDecimal niftyLevel) {
        return Math.abs(niftyLevel.subtract(BigDecimal.valueOf(PE_STRIKE)).doubleValue());
    }
}
