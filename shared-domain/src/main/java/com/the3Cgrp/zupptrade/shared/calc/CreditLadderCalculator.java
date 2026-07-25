package com.the3Cgrp.zupptrade.shared.calc;

import com.the3Cgrp.zupptrade.shared.enums.OptionType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, deterministic calculator for a credit spread's T1/T2/T3 monitoring ladder.
 *
 * <p>ONE source of truth used by both Agent 2 (at trade entry) and Agent 3 (every monitor cycle),
 * so the level math is identical everywhere. Stateless — same inputs always produce the same output.
 *
 * <h2>Model</h2>
 * The three exit rungs are defined as <b>fractions of the seller PoP at trade entry</b>, not fixed
 * PoP values — so the ladder scales with how the strike was chosen (default 80% gate, or a
 * user-overridden entry PoP). With the default fractions:
 * <pre>
 *   T1 watch    PoP = 0.8750 × entryPoP   (→ 70% when entry is 80%)
 *   T2 readjust PoP = 0.8000 × entryPoP   (→ 64% when entry is 80%)
 *   T3 exit     PoP = 0.7125 × entryPoP   (→ 57% when entry is 80%)
 * </pre>
 * The target PoPs are fixed at entry; the <b>Nifty levels</b> for those PoPs are recomputed every
 * cycle from the live IV and the current DTE via an analytical Black-Scholes inversion, so they
 * tighten as DTE decays (gamma) and widen when IV/VIX rises.
 *
 * <h2>Static distance floors (defensive ordering guarantee)</h2>
 * Each rung has a minimum distance from the short strike — T1 ±125, T2 ±100, T3 ±75 pts. The level
 * is placed at whichever is <b>farther</b> from the short strike between the PoP-inverted level and
 * the floor (PE → higher Nifty, CE → lower Nifty). This (a) guarantees an exit at least ±75 pts
 * before the short strike, and (b) because 125 &gt; 100 &gt; 75, guarantees the ordering
 * T1 &gt; T2 &gt; T3 (PE) / T1 &lt; T2 &lt; T3 (CE) even when the PoP contours collapse near expiry.
 *
 * <p>Note: DTE=0 is handled separately by Agent 3's expiry-day proximity logic — this calculator is
 * not the decision path at expiry. When DTE ≤ 0 or IV ≤ 0 the inversion is degenerate, so the
 * levels fall back to the pure distance floors (never throws).
 *
 * <p>Transcendental math (exp/log/sqrt, inverse normal CDF) runs in {@code double} then converts back
 * to {@link BigDecimal} — the same pattern the Black-Scholes calculators use. Money/score values are
 * never held as double.
 */
public final class CreditLadderCalculator {

    // Default PoP fractions of entry PoP. 0.80 entry → 0.70 / 0.64 / 0.57.
    public static final BigDecimal DEFAULT_T1_FRACTION = new BigDecimal("0.8750");
    public static final BigDecimal DEFAULT_T2_FRACTION = new BigDecimal("0.8000");
    public static final BigDecimal DEFAULT_T3_FRACTION = new BigDecimal("0.7125");

    // Default static distance floors from the short strike (points). T1 > T2 > T3 guarantees ordering.
    public static final int DEFAULT_T1_FLOOR_PTS = 125;
    public static final int DEFAULT_T2_FLOOR_PTS = 100;
    public static final int DEFAULT_T3_FLOOR_PTS = 75;

    private CreditLadderCalculator() {}

    /** The three fixed-at-entry target PoPs (0..1). */
    public record TargetPops(BigDecimal t1, BigDecimal t2, BigDecimal t3) {}

    /** Target PoPs plus the Nifty levels those PoPs map to at a given IV/DTE. */
    public record Ladder(BigDecimal t1Pop, BigDecimal t2Pop, BigDecimal t3Pop,
                         BigDecimal t1Nifty, BigDecimal t2Nifty, BigDecimal t3Nifty) {}

    // ── Entry: derive the fixed target PoPs from the seller PoP at entry ──────────────

    /** Target PoPs using the default fractions. */
    public static TargetPops targetPops(BigDecimal entryPop) {
        return targetPops(entryPop, DEFAULT_T1_FRACTION, DEFAULT_T2_FRACTION, DEFAULT_T3_FRACTION);
    }

    /** Target PoPs using explicit fractions (config-driven). */
    public static TargetPops targetPops(BigDecimal entryPop,
                                        BigDecimal t1Fraction, BigDecimal t2Fraction, BigDecimal t3Fraction) {
        if (entryPop == null || entryPop.signum() <= 0) {
            throw new IllegalArgumentException("entryPop must be > 0, was " + entryPop);
        }
        return new TargetPops(
                entryPop.multiply(t1Fraction).setScale(4, RoundingMode.HALF_UP),
                entryPop.multiply(t2Fraction).setScale(4, RoundingMode.HALF_UP),
                entryPop.multiply(t3Fraction).setScale(4, RoundingMode.HALF_UP));
    }

    // ── Per cycle: map fixed target PoPs → Nifty levels at the live IV/DTE ────────────

    /** Nifty levels for the given fixed target PoPs, using default distance floors. */
    public static Ladder levels(int shortStrike, OptionType shortType, TargetPops pops,
                                BigDecimal iv, int dte, BigDecimal riskFreeRate) {
        return levels(shortStrike, shortType, pops, iv, dte, riskFreeRate,
                DEFAULT_T1_FLOOR_PTS, DEFAULT_T2_FLOOR_PTS, DEFAULT_T3_FLOOR_PTS);
    }

    /** Nifty levels for the given fixed target PoPs, with explicit distance floors (config-driven). */
    public static Ladder levels(int shortStrike, OptionType shortType, TargetPops pops,
                                BigDecimal iv, int dte, BigDecimal riskFreeRate,
                                int t1FloorPts, int t2FloorPts, int t3FloorPts) {
        BigDecimal t1 = level(shortStrike, shortType, pops.t1(), iv, dte, riskFreeRate, t1FloorPts);
        BigDecimal t2 = level(shortStrike, shortType, pops.t2(), iv, dte, riskFreeRate, t2FloorPts);
        BigDecimal t3 = level(shortStrike, shortType, pops.t3(), iv, dte, riskFreeRate, t3FloorPts);
        return new Ladder(pops.t1(), pops.t2(), pops.t3(), t1, t2, t3);
    }

    /** Convenience for entry: entryPoP → target PoPs → Nifty levels (default fractions + floors). */
    public static Ladder compute(int shortStrike, OptionType shortType, BigDecimal entryPop,
                                 BigDecimal iv, int dte, BigDecimal riskFreeRate) {
        return levels(shortStrike, shortType, targetPops(entryPop), iv, dte, riskFreeRate);
    }

    // ── Internals ────────────────────────────────────────────────────────────────────

    /**
     * One rung: the more defensive of {PoP-inverted level, static distance floor}.
     * PE short → level sits above the strike (danger is spot falling) → take the higher.
     * CE short → level sits below the strike (danger is spot rising)  → take the lower.
     */
    private static BigDecimal level(int shortStrike, OptionType shortType, BigDecimal targetPop,
                                    BigDecimal iv, int dte, BigDecimal riskFreeRate, int floorPts) {
        BigDecimal floor = (shortType == OptionType.PE)
                ? BigDecimal.valueOf((long) shortStrike + floorPts)
                : BigDecimal.valueOf((long) shortStrike - floorPts);

        BigDecimal inverted = inversePopSpot(shortStrike, iv, dte, riskFreeRate, targetPop, shortType);
        if (inverted == null) {
            return floor; // degenerate (DTE≤0 / IV≤0) — pure distance floor
        }
        BigDecimal chosen = (shortType == OptionType.PE) ? inverted.max(floor) : inverted.min(floor);
        return chosen.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Nifty spot where the seller PoP of the short option equals targetPop.
     * Analytical inversion of Black-Scholes d2:
     *   short PUT:  PoP = N(d2)  → d2 =  N⁻¹(targetPop)
     *   short CALL: PoP = N(-d2) → d2 = -N⁻¹(targetPop)
     *   S = K · exp(d2·σ√t − (r − 0.5σ²)t)
     * Returns null when IV/DTE/targetPop are degenerate.
     */
    private static BigDecimal inversePopSpot(int strike, BigDecimal ivBd, int dte,
                                             BigDecimal rBd, BigDecimal targetPopBd, OptionType type) {
        double sigma = ivBd == null ? 0.0 : ivBd.doubleValue();
        double t = dte / 365.0;
        double targetPop = targetPopBd.doubleValue();
        if (sigma <= 0.0 || t <= 0.0 || targetPop <= 0.0 || targetPop >= 1.0) {
            return null;
        }
        double r = rBd.doubleValue();
        double sqrtT = Math.sqrt(t);
        double d2Target = inverseNormalCdf(targetPop);
        double d2 = (type == OptionType.PE) ? d2Target : -d2Target;
        double lnSK = d2 * sigma * sqrtT - (r - 0.5 * sigma * sigma) * t;
        return BigDecimal.valueOf(strike * Math.exp(lnSK));
    }

    // Peter Acklam's rational approximation for the inverse normal CDF. Max abs error ~1.15e-9.
    private static double inverseNormalCdf(double p) {
        final double[] a = { -3.969683028665376e+01,  2.209460984245205e+02,
                             -2.759285104469687e+02,  1.383577518672690e+02,
                             -3.066479806614716e+01,  2.506628277459239e+00 };
        final double[] b = { -5.447609879822406e+01,  1.615858368580409e+02,
                             -1.556989798598866e+02,  6.680131188771972e+01,
                             -1.328068155288572e+01 };
        final double[] c = { -7.784894002430293e-03, -3.223964580411365e-01,
                             -2.400758277161838e+00, -2.549732539343734e+00,
                              4.374664141464968e+00,  2.938163982698783e+00 };
        final double[] d = {  7.784695709041462e-03,  3.224671290700398e-01,
                              2.445134137142996e+00,  3.754408661907416e+00 };
        final double pLow = 0.02425;
        double q, r, x;
        if (p < pLow) {
            q = Math.sqrt(-2.0 * Math.log(p));
            x = (((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5])
              / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1.0);
        } else if (p <= 1.0 - pLow) {
            q = p - 0.5;
            r = q * q;
            x = (((((a[0]*r+a[1])*r+a[2])*r+a[3])*r+a[4])*r+a[5])*q
              / (((((b[0]*r+b[1])*r+b[2])*r+b[3])*r+b[4])*r+1.0);
        } else {
            q = Math.sqrt(-2.0 * Math.log(1.0 - p));
            x = -(((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5])
               / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1.0);
        }
        return x;
    }
}
