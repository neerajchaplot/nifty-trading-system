package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.shared.enums.ArmReachability;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reachability is a direction-agnostic band test: REACHABLE while the live level sits inside the
 * arm's stop↔target band (inclusive), MISSED once it leaves the band, null when the level is unknown.
 */
class ArmReachabilityCalculatorTest {

    private final ArmReachabilityCalculator calc = new ArmReachabilityCalculator();

    // Long-rotation shape: stop below, target above.
    private static final BigDecimal STOP = new BigDecimal("24237.64");
    private static final BigDecimal TARGET = new BigDecimal("24356.92");

    @Test
    void insideBand_isReachable() {
        assertThat(calc.evaluate(new BigDecimal("24300"), STOP, TARGET)).isEqualTo(ArmReachability.REACHABLE);
    }

    @Test
    void aboveTarget_isMissed() {
        assertThat(calc.evaluate(new BigDecimal("24400"), STOP, TARGET)).isEqualTo(ArmReachability.MISSED);
    }

    @Test
    void belowStop_isMissed() {
        assertThat(calc.evaluate(new BigDecimal("24200"), STOP, TARGET)).isEqualTo(ArmReachability.MISSED);
    }

    @Test
    void bandEdges_areInclusive_soReachable() {
        assertThat(calc.evaluate(STOP, STOP, TARGET)).isEqualTo(ArmReachability.REACHABLE);
        assertThat(calc.evaluate(TARGET, STOP, TARGET)).isEqualTo(ArmReachability.REACHABLE);
    }

    @Test
    void directionAgnostic_shortArm_stopAboveTarget() {
        // Short-rotation shape: stop above (24396.68), target below (24277.40). Same band, same verdict.
        BigDecimal shortStop = new BigDecimal("24396.68");
        BigDecimal shortTarget = new BigDecimal("24277.40");
        assertThat(calc.evaluate(new BigDecimal("24300"), shortStop, shortTarget))
                .isEqualTo(ArmReachability.REACHABLE);
        assertThat(calc.evaluate(new BigDecimal("24450"), shortStop, shortTarget))
                .isEqualTo(ArmReachability.MISSED);
    }

    @Test
    void nullLevel_isUndetermined() {
        assertThat(calc.evaluate(null, STOP, TARGET)).isNull();
    }
}
