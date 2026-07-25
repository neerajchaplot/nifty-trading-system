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

    // ── Option-type-aware PoP (bear call spread override fix) ────────────────
    // The Override Builder for a BEAR_CALL_SPREAD used to price the short CALL strike
    // as a PUT, producing a low PoP that tripped the ≥50% floor and blocked placement.
    // The same 24600 short strike must read LOW as a put but HIGH as a call.

    @Test
    void calculatePop_shortCall_otmAboveSpot_returnsHighPop() {
        // Bear call: spot 24069, short 24600 CE (OTM). Call expires worthless if spot stays below 24600 → high PoP.
        BigDecimal cePop = calculator.calculatePop(
                new BigDecimal("24069.15"), new BigDecimal("24600"),
                new BigDecimal("0.1375"), 6, new BigDecimal("0.065"), OptionType.CE);
        assertThat(cePop).isGreaterThan(new BigDecimal("0.70")); // passes the 50% override floor
    }

    @Test
    void calculatePop_samePutStrike_readsLow_whileCallReadsHigh() {
        BigDecimal spot = new BigDecimal("24069.15");
        BigDecimal strike = new BigDecimal("24600");
        BigDecimal iv = new BigDecimal("0.1375");
        BigDecimal pePop = calculator.calculatePop(spot, strike, iv, 6, new BigDecimal("0.065"), OptionType.PE);
        BigDecimal cePop = calculator.calculatePop(spot, strike, iv, 6, new BigDecimal("0.065"), OptionType.CE);

        assertThat(pePop).isLessThan(new BigDecimal("0.30"));    // as a put → blocked (< 50%)
        assertThat(cePop).isGreaterThan(new BigDecimal("0.70")); // as a call → allowed
        // N(d2) + N(-d2) = 1 — call and put PoP on the same strike are complementary
        assertThat(pePop.add(cePop)).isCloseTo(BigDecimal.ONE, org.assertj.core.data.Offset.offset(new BigDecimal("0.005")));
    }

    @Test
    void calculatePop_legacyFiveArg_equalsPutOverload() {
        BigDecimal legacy = calculator.calculatePop(
                new BigDecimal("24000"), new BigDecimal("23500"), new BigDecimal("0.15"), 5, new BigDecimal("0.065"));
        BigDecimal explicitPut = calculator.calculatePop(
                new BigDecimal("24000"), new BigDecimal("23500"), new BigDecimal("0.15"), 5, new BigDecimal("0.065"), OptionType.PE);
        assertThat(legacy).isEqualByComparingTo(explicitPut);
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
