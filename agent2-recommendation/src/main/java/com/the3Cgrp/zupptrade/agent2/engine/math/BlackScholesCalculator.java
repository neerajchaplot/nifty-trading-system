package com.the3Cgrp.zupptrade.agent2.engine.math;

import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Pure Black-Scholes calculations. All methods are stateless — same inputs always produce same outputs.
 * Used for expected move calculation and PoP cross-check when Upstox greeks are unavailable.
 */
@Component
public class BlackScholesCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int SCALE = 6;

    public BigDecimal calculatePop(BigDecimal spot, BigDecimal strike, BigDecimal iv, int dte, BigDecimal riskFreeRate) {
        double S = spot.doubleValue();
        double K = strike.doubleValue();
        double sigma = iv.doubleValue();
        double t = dte / 365.0;
        double r = riskFreeRate.doubleValue();

        if (sigma <= 0 || t <= 0) {
            return spot.compareTo(strike) > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        }

        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * t) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;

        // PoP for short put = N(d2) — probability the put expires worthless
        return BigDecimal.valueOf(normalCdf(d2)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateDelta(BigDecimal spot, BigDecimal strike, BigDecimal iv, int dte,
                                     BigDecimal riskFreeRate, OptionType optionType) {
        double S = spot.doubleValue();
        double K = strike.doubleValue();
        double sigma = iv.doubleValue();
        double t = dte / 365.0;
        double r = riskFreeRate.doubleValue();

        if (sigma <= 0 || t <= 0) {
            return BigDecimal.ZERO;
        }

        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * t) / (sigma * sqrtT);

        double delta = switch (optionType) {
            case CE -> normalCdf(d1);
            case PE -> normalCdf(d1) - 1.0;
        };

        return BigDecimal.valueOf(delta).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal expectedMove(BigDecimal spot, BigDecimal iv, int dte) {
        // EM = Spot × IV × √(DTE/365)
        double em = spot.doubleValue() * iv.doubleValue() * Math.sqrt(dte / 365.0);
        return BigDecimal.valueOf(em).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the Nifty spot level at which the PoP of a short option equals targetPop.
     * Analytical inversion of Black-Scholes d2:
     *   Short PUT:  PoP = N(d2)  → d2 = N_inv(targetPop)
     *   Short CALL: PoP = N(-d2) → d2 = -N_inv(targetPop)
     *   S = K × exp(d2 × σ√t − (r − 0.5σ²)t)
     *
     * Returns null when IV or DTE is degenerate — caller must provide a fallback.
     */
    public BigDecimal inversePopSpot(BigDecimal strike, BigDecimal iv, int dte,
                                     BigDecimal riskFreeRate, double targetPop,
                                     OptionType optionType) {
        double K = strike.doubleValue();
        double sigma = iv.doubleValue();
        double t = dte / 365.0;
        double r = riskFreeRate.doubleValue();

        if (sigma <= 0 || t <= 0) return null;

        double sqrtT = Math.sqrt(t);
        double d2Target = inverseNormalCdf(targetPop);
        double d2 = (optionType == OptionType.PE) ? d2Target : -d2Target;
        double lnSK = d2 * sigma * sqrtT - (r - 0.5 * sigma * sigma) * t;
        double S = K * Math.exp(lnSK);
        return BigDecimal.valueOf(S).setScale(2, RoundingMode.HALF_UP);
    }

    // Abramowitz and Stegun approximation — accurate to 7.5e-8
    private double normalCdf(double x) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double d = 0.3989422820 * Math.exp(-x * x / 2.0);
        double p = d * t * (0.3193815530 + t * (-0.3565637910 + t * (1.7814779370 + t * (-1.8212559780 + t * 1.3302744290))));
        return x > 0 ? 1.0 - p : p;
    }

    // Peter Acklam's rational approximation for the inverse normal CDF.
    // Max absolute error ~1.15e-9 for 0 < p < 1.
    private static double inverseNormalCdf(double p) {
        if (p <= 0.0) return Double.NEGATIVE_INFINITY;
        if (p >= 1.0) return Double.POSITIVE_INFINITY;

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
