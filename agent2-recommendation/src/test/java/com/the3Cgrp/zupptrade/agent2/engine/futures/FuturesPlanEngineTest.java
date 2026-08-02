package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.config.FuturesConfig;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmPlan;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.FuturesPlanInputs;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.FuturesPlanResult;
import com.the3Cgrp.zupptrade.shared.enums.ArmCardStatus;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.OpenZone;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end composition of the futures engine (spec §2–§6), on the 31-Jul worked example.
 * Prior-day OHLC: H 24341.39 / L 24196.81 / C 24317.16 → the spec's published levels.
 */
class FuturesPlanEngineTest {

    private final FuturesPlanEngine engine = new FuturesPlanEngine(
            new CamarillaCalculator(), new OpenClassifier(), new FourArmGridBuilder(),
            new ArmSelector(), new ProbabilityRanker(), new RiskRewardCalculator(),
            new CostModel(), new CompressionGate(), new PositionSizer(), new MarginEstimator(),
            new ConfidenceGate());

    private final FuturesConfig cfg = new FuturesConfig(); // defaults: conf 0.40, compression 0.70, risk 1%

    private static final BigDecimal H = new BigDecimal("24341.39");
    private static final BigDecimal L = new BigDecimal("24196.81");
    private static final BigDecimal C = new BigDecimal("24317.16");
    private static final BigDecimal CAPITAL = new BigDecimal("500000");

    private ArmPlan arm(FuturesPlanResult r, FutureArmType t) {
        return r.arms().stream().filter(a -> a.arm().type() == t).findFirst().orElseThrow();
    }

    @Test
    void bullishRangeHighConfidence_recommendsLongRotation_notCompressed() {
        // Open 24300 mid-range; 20-day SMA 150 vs prev range 144.58 → RCI 0.96, not compressed.
        FuturesPlanInputs in = new FuturesPlanInputs(H, L, C, new BigDecimal("24300"),
                Bias.BULLISH, new BigDecimal("0.80"),
                new BigDecimal("144.58"), Collections.nCopies(20, new BigDecimal("150")),
                65, CAPITAL);

        FuturesPlanResult r = engine.plan(in, cfg);

        assertThat(r.openZone()).isEqualTo(OpenZone.RANGE);
        assertThat(r.primaryArm()).isEqualTo(FutureArmType.LONG_ROTATION);
        assertThat(r.planNoTrade()).isFalse();
        assertThat(r.compression().compressed()).isFalse();
        assertThat(r.arms()).hasSize(4);

        ArmPlan longRot = arm(r, FutureArmType.LONG_ROTATION);
        assertThat(longRot.status()).isEqualTo(ArmCardStatus.RECOMMENDED);
        assertThat(longRot.rr().rrGross()).isEqualByComparingTo("2.0");
        // riskCapital 5000, stop 39.76 pts × 65 = 2584.40/lot → 1 lot
        assertThat(longRot.sizing().lots()).isEqualTo(1);
        assertThat(longRot.probabilityPct().doubleValue()).isGreaterThan(40.0);

        assertThat(arm(r, FutureArmType.SHORT_ROTATION).status()).isEqualTo(ArmCardStatus.ALLOWED);
        assertThat(arm(r, FutureArmType.LONG_BREAKOUT).status()).isEqualTo(ArmCardStatus.ALLOWED);
    }

    @Test
    void compressedDay_blocksRotation_keepsBreakoutSelectable() {
        // prev range 100 vs SMA 300 → RCI 0.33 < 0.70 → compressed.
        FuturesPlanInputs in = new FuturesPlanInputs(H, L, C, new BigDecimal("24300"),
                Bias.BULLISH, new BigDecimal("0.80"),
                new BigDecimal("100"), Collections.nCopies(20, new BigDecimal("300")),
                65, CAPITAL);

        FuturesPlanResult r = engine.plan(in, cfg);

        assertThat(r.compression().compressed()).isTrue();
        assertThat(arm(r, FutureArmType.LONG_ROTATION).status()).isEqualTo(ArmCardStatus.BLOCKED);
        assertThat(arm(r, FutureArmType.LONG_ROTATION).blockedReason()).contains("coiled");
        assertThat(arm(r, FutureArmType.LONG_BREAKOUT).status()).isNotEqualTo(ArmCardStatus.BLOCKED);
        assertThat(r.primaryArm()).isNull();          // primary rotation was vetoed
        assertThat(r.planNoTrade()).isFalse();         // breakout still available
    }

    @Test
    void lowConfidence_blocksEveryArm_isNoTrade() {
        FuturesPlanInputs in = new FuturesPlanInputs(H, L, C, new BigDecimal("24300"),
                Bias.BULLISH, new BigDecimal("0.30"),   // below 0.40 gate
                new BigDecimal("144.58"), Collections.nCopies(20, new BigDecimal("150")),
                65, CAPITAL);

        FuturesPlanResult r = engine.plan(in, cfg);

        assertThat(r.confidenceGate().passed()).isFalse();
        assertThat(r.arms()).allSatisfy(a -> assertThat(a.status()).isEqualTo(ArmCardStatus.BLOCKED));
        assertThat(r.planNoTrade()).isTrue();
        assertThat(r.planNoTradeReason()).contains("weak");
    }

    @Test
    void neutralBias_rangeFadesBothRotationArms() {
        FuturesPlanInputs in = new FuturesPlanInputs(H, L, C, new BigDecimal("24300"),
                Bias.NEUTRAL, new BigDecimal("0.60"),
                new BigDecimal("144.58"), Collections.nCopies(20, new BigDecimal("150")),
                65, CAPITAL);

        FuturesPlanResult r = engine.plan(in, cfg);

        assertThat(r.rangeFade()).isTrue();
        assertThat(arm(r, FutureArmType.LONG_ROTATION).status()).isEqualTo(ArmCardStatus.RECOMMENDED);
        assertThat(arm(r, FutureArmType.SHORT_ROTATION).status()).isEqualTo(ArmCardStatus.RECOMMENDED);
    }
}
