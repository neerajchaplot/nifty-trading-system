package com.the3Cgrp.zupptrade.agent3.math;

import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BlackScholesCalculatorTest {

    private final BlackScholesCalculator calc = new BlackScholesCalculator();

    // Deep OTM short put — PoP should be very high
    @Test
    void calculatePop_shortPut_deepOtm_returnsHighPop() {
        // Spot=24000, Strike=23500 (500 pts OTM), IV=15.9%, DTE=5
        BigDecimal pop = calc.calculatePop(
                new BigDecimal("24000"), new BigDecimal("23500"),
                new BigDecimal("0.159"), 5, new BigDecimal("0.065"), OptionType.PE);
        assertThat(pop).isGreaterThan(new BigDecimal("0.85"));
    }

    // ATM short put — PoP ≈ 50%
    @Test
    void calculatePop_shortPut_atm_returnsApprox50Pct() {
        BigDecimal pop = calc.calculatePop(
                new BigDecimal("24000"), new BigDecimal("24000"),
                new BigDecimal("0.15"), 5, new BigDecimal("0.065"), OptionType.PE);
        assertThat(pop).isBetween(new BigDecimal("0.45"), new BigDecimal("0.55"));
    }

    // Deep OTM short call — PoP should be very high (spot well below strike)
    @Test
    void calculatePop_shortCall_deepOtm_returnsHighPop() {
        // Spot=24000, Strike=24500 (500 pts OTM for call), IV=15%, DTE=5
        BigDecimal pop = calc.calculatePop(
                new BigDecimal("24000"), new BigDecimal("24500"),
                new BigDecimal("0.15"), 5, new BigDecimal("0.065"), OptionType.CE);
        assertThat(pop).isGreaterThan(new BigDecimal("0.80"));
    }

    // PoP for put + PoP for call at same strike should sum > 1 (due to drift) but each < 1
    @Test
    void calculatePop_putAndCallSameStrike_eachLessThanOne() {
        BigDecimal putPop = calc.calculatePop(
                new BigDecimal("24000"), new BigDecimal("23800"),
                new BigDecimal("0.18"), 3, new BigDecimal("0.065"), OptionType.PE);
        BigDecimal callPop = calc.calculatePop(
                new BigDecimal("24000"), new BigDecimal("23800"),
                new BigDecimal("0.18"), 3, new BigDecimal("0.065"), OptionType.CE);
        assertThat(putPop).isLessThan(BigDecimal.ONE);
        assertThat(callPop).isLessThan(BigDecimal.ONE);
    }

    // DTE=0 and spot > strike → put expires OTM → PoP = 1
    @Test
    void calculatePop_zeroDte_spotAboveStrike_putReturnsOne() {
        BigDecimal pop = calc.calculatePop(
                new BigDecimal("24000"), new BigDecimal("23500"),
                new BigDecimal("0.15"), 0, new BigDecimal("0.065"), OptionType.PE);
        assertThat(pop).isEqualByComparingTo(BigDecimal.ONE);
    }

    // DTE=0 and spot < strike → put is ITM → PoP = 0
    @Test
    void calculatePop_zeroDte_spotBelowStrike_putReturnsZero() {
        BigDecimal pop = calc.calculatePop(
                new BigDecimal("23400"), new BigDecimal("23500"),
                new BigDecimal("0.15"), 0, new BigDecimal("0.065"), OptionType.PE);
        assertThat(pop).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
