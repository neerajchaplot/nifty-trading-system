package com.the3Cgrp.zupptrade.agent2.engine.math;

import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BlackScholesCalculatorTest {

    private final BlackScholesCalculator calculator = new BlackScholesCalculator();

    // Validated against worked example from context doc:
    // Spot: 23998, VIX: 18.46%, ATM IV: 15.9%, DTE: 2, Strike: 23500 PE → PoP ~96.5%
    @Test
    void calculatePop_shortPutDeepOtm_returnsHighPop() {
        BigDecimal pop = calculator.calculatePop(
                new BigDecimal("23998"),
                new BigDecimal("23500"),
                new BigDecimal("0.159"),
                2,
                new BigDecimal("0.065")
        );
        assertThat(pop).isGreaterThan(new BigDecimal("0.90")); // deep OTM put should have high PoP
    }

    @Test
    void calculatePop_atmStrike_returnsApprox50Pct() {
        BigDecimal pop = calculator.calculatePop(
                new BigDecimal("24000"),
                new BigDecimal("24000"),
                new BigDecimal("0.15"),
                5,
                new BigDecimal("0.065")
        );
        assertThat(pop).isBetween(new BigDecimal("0.45"), new BigDecimal("0.55"));
    }

    @Test
    void calculateDelta_deepOtmPut_returnsSmallNegativeDelta() {
        BigDecimal delta = calculator.calculateDelta(
                new BigDecimal("24000"),
                new BigDecimal("23500"),
                new BigDecimal("0.15"),
                5,
                new BigDecimal("0.065"),
                OptionType.PE
        );
        assertThat(delta).isLessThan(new BigDecimal("-0.01"));
        assertThat(delta).isGreaterThan(new BigDecimal("-0.25"));
    }

    @Test
    void expectedMove_computesCorrectly() {
        // EM = 23998 × 0.159 × √(2/365) ≈ 281 from worked example
        BigDecimal em = calculator.expectedMove(
                new BigDecimal("23998"),
                new BigDecimal("0.159"),
                2
        );
        assertThat(em).isGreaterThan(BigDecimal.valueOf(250));
        assertThat(em).isLessThan(BigDecimal.valueOf(320));
    }

    @Test
    void calculatePop_zeroDte_returnsOneOrZero() {
        BigDecimal pop = calculator.calculatePop(
                new BigDecimal("24000"),
                new BigDecimal("23500"),
                new BigDecimal("0.15"),
                0,
                new BigDecimal("0.065")
        );
        // DTE=0 → option expired OTM (spot > strike) → PoP = 1
        assertThat(pop).isEqualByComparingTo(BigDecimal.ONE);
    }
}
