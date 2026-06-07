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

    // Abramowitz and Stegun approximation — accurate to 7.5e-8
    private double normalCdf(double x) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double d = 0.3989422820 * Math.exp(-x * x / 2.0);
        double p = d * t * (0.3193815530 + t * (-0.3565637910 + t * (1.7814779370 + t * (-1.8212559780 + t * 1.3302744290))));
        return x > 0 ? 1.0 - p : p;
    }
}
