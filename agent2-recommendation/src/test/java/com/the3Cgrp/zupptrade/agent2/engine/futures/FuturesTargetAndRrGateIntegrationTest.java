package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.config.FuturesConfig;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmPlan;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.FuturesPlanInputs;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.FuturesPlanResult;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration of the real futures engine (no mocks) covering the two linked changes:
 *   1. breakout/breakdown targets are the Camarilla H5/L5 extension (not the old nearest round-50), and
 *   2. the R:R gate hides any arm whose after-cost R:R is at/below the floor (default 0.5).
 *
 * The two interact: with real structural targets the breakout/breakdown arms clear the gate on a
 * normal-range day; on a very tight-range day their reward no longer covers cost, so the gate removes
 * them while the rotation arms (R:R ~2.0) survive.
 */
class FuturesTargetAndRrGateIntegrationTest {

    private final FuturesPlanEngine engine = new FuturesPlanEngine(
            new CamarillaCalculator(), new OpenClassifier(), new FourArmGridBuilder(),
            new ArmSelector(), new ProbabilityRanker(), new RiskRewardCalculator(),
            new CostModel(), new CompressionGate(), new PositionSizer(), new MarginEstimator(),
            new ConfidenceGate());

    private final FuturesConfig cfg = new FuturesConfig(); // minConfidence 0.40, minRrAfterCost 0.5

    private static final BigDecimal CAPITAL = new BigDecimal("500000");

    private Optional<ArmPlan> arm(FuturesPlanResult r, FutureArmType t) {
        return r.arms().stream().filter(a -> a.arm().type() == t).findFirst();
    }

    @Test
    void breakoutAndBreakdownTargets_areH5L5_notRound50_andSurviveTheGate() {
        // Spec's 31-Jul prior day → H5 ≈ 24462.46, L5 ≈ 24171.86. Open mid-range (RANGE), bullish.
        FuturesPlanInputs in = new FuturesPlanInputs(
                new BigDecimal("24341.39"), new BigDecimal("24196.81"), new BigDecimal("24317.16"),
                new BigDecimal("24300"), Bias.BULLISH, new BigDecimal("0.80"),
                new BigDecimal("144.58"), Collections.nCopies(20, new BigDecimal("150")),
                65, CAPITAL);

        FuturesPlanResult r = engine.plan(in, cfg);

        // All four arms survive on a normal-range day.
        assertThat(r.arms()).hasSize(4);

        ArmPlan longBrk = arm(r, FutureArmType.LONG_BREAKOUT).orElseThrow();
        ArmPlan shortBrk = arm(r, FutureArmType.SHORT_BREAKDOWN).orElseThrow();

        // Targets are the structural extension levels, not a round-50 (old bug gave 24450 / 24200).
        assertThat(longBrk.arm().target()).isEqualByComparingTo(r.camarilla().h5());
        assertThat(shortBrk.arm().target()).isEqualByComparingTo(r.camarilla().l5());
        assertThat(r.camarilla().h5().doubleValue()).isCloseTo(24462.46, org.assertj.core.api.Assertions.within(0.1));
        assertThat(r.camarilla().l5().doubleValue()).isCloseTo(24171.86, org.assertj.core.api.Assertions.within(0.1));

        // Both clear the 0.5 gate on a normal-range day.
        assertThat(longBrk.rr().rrAfterCost()).isGreaterThan(new BigDecimal("0.5"));
        assertThat(shortBrk.rr().rrAfterCost()).isGreaterThan(new BigDecimal("0.5"));
    }

    @Test
    void rrGate_hidesBreakoutAndBreakdown_onTightRangeDay() {
        // A ~28-pt range day: breakout/breakdown reward no longer covers round-trip cost, so their
        // after-cost R:R falls to ~0.4 (≤ 0.5) and the gate removes them; rotation arms (~2.0) remain.
        FuturesPlanInputs in = new FuturesPlanInputs(
                new BigDecimal("22519"), new BigDecimal("22491"), new BigDecimal("22505"),
                new BigDecimal("22505"), Bias.BULLISH, new BigDecimal("0.80"),
                new BigDecimal("28"), Collections.nCopies(20, new BigDecimal("28")),
                65, CAPITAL);

        FuturesPlanResult r = engine.plan(in, cfg);

        // Breakout/breakdown hidden entirely — not shown, not selectable.
        assertThat(arm(r, FutureArmType.LONG_BREAKOUT)).isEmpty();
        assertThat(arm(r, FutureArmType.SHORT_BREAKDOWN)).isEmpty();
        // Rotation arms survive.
        assertThat(arm(r, FutureArmType.LONG_ROTATION)).isPresent();
        assertThat(arm(r, FutureArmType.SHORT_ROTATION)).isPresent();
        assertThat(r.arms()).hasSize(2);
    }
}
