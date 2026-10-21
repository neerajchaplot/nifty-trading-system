package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.CamarillaLevels;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.FuturesArm;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Builds the four-arm grid entry/stop/target levels (spec §2.3 + §4 per-arm table).
 *
 * <pre>
 * Arm             entry            stop        target
 * 1 Long rotation L3 reclaim       below L4    pivot → H3
 * 2 Short rotation H3 reject       above H4    pivot → L3
 * 3 Long breakout above H4+buffer  H3          H5 (extension)
 * 4 Short breakdown below L4-buffer L3          L5 (extension)
 * </pre>
 *
 * buffer ≈ 0.1 × (H4 − H3) gap (spec §3). Rotation entries sit on the band level
 * itself; breakout/breakdown entries clear the band by the buffer so a wick alone
 * does not trigger. Breakout/breakdown targets are the Camarilla H5/L5 extension —
 * a real structural level in the trade direction, not the nearest round-50 (which
 * produced degenerate few-point targets whenever entry sat just past a 50 boundary).
 */
@Component
public class FourArmGridBuilder {

    private static final BigDecimal BUFFER_FACTOR = new BigDecimal("0.1");
    private static final int SCALE = 2;

    public List<FuturesArm> build(CamarillaLevels c) {
        BigDecimal buffer = c.h4().subtract(c.h3())
                .multiply(BUFFER_FACTOR).setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal breakoutEntry = c.h4().add(buffer);
        BigDecimal breakdownEntry = c.l4().subtract(buffer);

        FuturesArm longRotation = new FuturesArm(
                FutureArmType.LONG_ROTATION, FutureArmType.LONG_ROTATION.direction(),
                c.l3(), c.l3(), c.l4(), c.h3());

        FuturesArm shortRotation = new FuturesArm(
                FutureArmType.SHORT_ROTATION, FutureArmType.SHORT_ROTATION.direction(),
                c.h3(), c.h3(), c.h4(), c.l3());

        FuturesArm longBreakout = new FuturesArm(
                FutureArmType.LONG_BREAKOUT, FutureArmType.LONG_BREAKOUT.direction(),
                c.h4(), breakoutEntry, c.h3(), c.h5());

        FuturesArm shortBreakdown = new FuturesArm(
                FutureArmType.SHORT_BREAKDOWN, FutureArmType.SHORT_BREAKDOWN.direction(),
                c.l4(), breakdownEntry, c.l3(), c.l5());

        return List.of(longRotation, shortRotation, longBreakout, shortBreakdown);
    }
}
