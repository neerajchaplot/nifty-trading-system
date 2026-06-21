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

    /** Abramowitz and Stegun approximation — accurate to 7.5e-8. */
    private double normalCdf(double x) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double d = 0.3989422820 * Math.exp(-x * x / 2.0);
        double p = d * t * (0.3193815530 + t * (-0.3565637910 + t * (1.7814779370 + t * (-1.8212559780 + t * 1.3302744290))));
        return x > 0 ? 1.0 - p : p;
    }
}
