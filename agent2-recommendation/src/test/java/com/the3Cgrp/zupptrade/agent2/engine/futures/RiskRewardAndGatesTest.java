package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmRiskReward;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ConfidenceGateResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * R:R + cost model (DISPLAY-ONLY) and the single confidence gate.
 *
 * R:R is anchored to the spec §5 worked examples (Arm 1 → 1.8, Arm 3 → 1.1) but is NOT a
 * hard gate — intraday you cannot guarantee R:R at the actual fill, so it is shown for the
 * user's judgement. The only hard gate is Agent 1 confidence.
 */
class RiskRewardAndGatesTest {

    private final RiskRewardCalculator rr = new RiskRewardCalculator();
    private final CostModel costModel = new CostModel();
    private final ConfidenceGate confidenceGate = new ConfidenceGate();

    private static final BigDecimal MIN_CONF = new BigDecimal("0.40");

    @Test
    void arm1LongRotation_grossRrIs1_8_forDisplay() {
        ArmRiskReward result = rr.compute(
                new BigDecimal("24280"), new BigDecimal("24237"), new BigDecimal("24357"),
                BigDecimal.ZERO);
        assertThat(result.riskPoints()).isEqualByComparingTo("43");
        assertThat(result.rewardPoints()).isEqualByComparingTo("77");
        assertThat(result.rrGross()).isEqualByComparingTo("1.8");
    }

    @Test
    void arm3LongBreakout_grossRrIs1_1_forDisplay() {
        ArmRiskReward result = rr.compute(
                new BigDecimal("24401"), new BigDecimal("24357"), new BigDecimal("24450"),
                BigDecimal.ZERO);
        assertThat(result.riskPoints()).isEqualByComparingTo("44");
        assertThat(result.rewardPoints()).isEqualByComparingTo("49");
        assertThat(result.rrGross()).isEqualByComparingTo("1.1");
    }

    @Test
    void costModel_subtractsRoundTripCostFromRewardForDisplay() {
        // 10-point round-trip cost on Arm 1: netReward 77-10=67 → 67/43 = 1.56 → 1.6 (shown, not gated).
        ArmRiskReward result = rr.compute(
                new BigDecimal("24280"), new BigDecimal("24237"), new BigDecimal("24357"),
                new BigDecimal("10"));
        assertThat(result.rrGross()).isEqualByComparingTo("1.8");
        assertThat(result.rrAfterCost()).isEqualByComparingTo("1.6");
    }

    @Test
    void costModel_pointsFormula_slippagePlusChargesPlusTaxes() {
        // entry 24280, ptValue 65, slippage 2, flat ₹100/RT, tax 0.0002 of turnover.
        // charges = 100/65 = 1.54 ; taxes = 24280×0.0002 = 4.86 ; total = 2 + 1.54 + 4.86 = 8.40
        BigDecimal pts = costModel.roundTripCostPoints(
                new BigDecimal("24280"), new BigDecimal("65"),
                new BigDecimal("2"), new BigDecimal("100"), new BigDecimal("0.0002"));
        assertThat(pts.doubleValue()).isCloseTo(8.40, within(0.01));
    }

    @Test
    void confidenceGate_isTheOnlyHardGate() {
        assertThat(confidenceGate.validate(new BigDecimal("0.80"), MIN_CONF).passed()).isTrue();
        assertThat(confidenceGate.validate(new BigDecimal("0.40"), MIN_CONF).passed()).isTrue(); // boundary
        assertThat(confidenceGate.validate(new BigDecimal("0.35"), MIN_CONF).passed()).isFalse();
    }
}
