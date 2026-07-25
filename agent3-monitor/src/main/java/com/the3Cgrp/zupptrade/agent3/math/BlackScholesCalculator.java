package com.the3Cgrp.zupptrade.agent3.math;

import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure Black-Scholes calculations for live PoP recalculation.
 * Stateless — same inputs always produce same outputs.
 *
 * Note: agent2 has its own copy of this class (cannot cross-module import).
 * If the algorithm changes here it must be kept in sync with agent2's copy.
 */
@Component
public class BlackScholesCalculator {

    private static final int SCALE = 6;

    /**
     * PoP for a short PUT: N(d2) — probability spot ends above strike at expiry.
     * PoP for a short CALL: N(-d2) — probability spot ends below strike at expiry.
     *
     * @param iv IV as decimal fraction (e.g. 0.172 for 17.2%). NOT percentage.
     */
    public BigDecimal calculatePop(BigDecimal spot, BigDecimal strike, BigDecimal iv,
                                   int dte, BigDecimal riskFreeRate, OptionType optionType) {
        double S = spot.doubleValue();
        double K = strike.doubleValue();
        double sigma = iv.doubleValue();
        double t = dte / 365.0;
        double r = riskFreeRate.doubleValue();

        if (sigma <= 0 || t <= 0) {
            return switch (optionType) {
                case PE -> spot.compareTo(strike) > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                case CE -> spot.compareTo(strike) < 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            };
        }

        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * t) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;

        double pop = switch (optionType) {
            case PE -> normalCdf(d2);       // short put: prob(spot > K) at expiry
            case CE -> normalCdf(-d2);      // short call: prob(spot < K) at expiry
        };

        return BigDecimal.valueOf(pop).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Nifty spot where the directional PoP of a reference level equals targetPop.
     * Analytical inversion of Black-Scholes d2 (same convention as calculatePop):
     *   PE reference (P spot &gt; level):  d2 =  N_inv(targetPop)
     *   CE reference (P spot &lt; level):  d2 = -N_inv(targetPop)
     *   S = level · exp(d2·σ√t − (r − 0.5σ²)t)
     * Returns null when IV/DTE/targetPop are degenerate — caller supplies a fallback.
     */
    public BigDecimal inversePopSpot(BigDecimal level, BigDecimal iv, int dte,
                                     BigDecimal riskFreeRate, double targetPop, OptionType optionType) {
        double K = level.doubleValue();
        double sigma = iv.doubleValue();
        double t = dte / 365.0;
        double r = riskFreeRate.doubleValue();
        if (sigma <= 0 || t <= 0 || targetPop <= 0.0 || targetPop >= 1.0) return null;

        double sqrtT = Math.sqrt(t);
        double d2Target = inverseNormalCdf(targetPop);
        double d2 = (optionType == OptionType.PE) ? d2Target : -d2Target;
        double lnSK = d2 * sigma * sqrtT - (r - 0.5 * sigma * sigma) * t;
        return BigDecimal.valueOf(K * Math.exp(lnSK)).setScale(0, RoundingMode.HALF_UP);
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

    /** Abramowitz and Stegun approximation — accurate to 7.5e-8. */
    private double normalCdf(double x) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double d = 0.3989422820 * Math.exp(-x * x / 2.0);
        double p = d * t * (0.3193815530 + t * (-0.3565637910 + t * (1.7814779370 + t * (-1.8212559780 + t * 1.3302744290))));
        return x > 0 ? 1.0 - p : p;
    }
}
