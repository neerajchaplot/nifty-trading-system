package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.CompressionResult;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Compression / stand-aside gate (spec §6.3):
 * <pre>
 * RCI = range(t-1) / SMA(range, 20)
 * </pre>
 * A low RCI means yesterday's range was small versus the 20-day average — the market
 * is compressed and whipsaw risk exceeds edge, so the day resolves to NO_TRADE even
 * on neutral days. The threshold is calibratable.
 */
@Component
public class CompressionGate {

    private static final int SCALE = 3;

    /**
     * @param prevDayRange  range (H-L) of the prior completed session, t-1
     * @param last20Ranges  the ranges of the last 20 completed sessions (for the SMA)
     * @param threshold     RCI below this → compressed (NO_TRADE)
     */
    public CompressionResult evaluate(BigDecimal prevDayRange, List<BigDecimal> last20Ranges,
                                      BigDecimal threshold) {
        if (last20Ranges == null || last20Ranges.isEmpty()) {
            // Cannot assess compression → do not block on missing data; treat as not compressed.
            return new CompressionResult(BigDecimal.ZERO, threshold, false);
        }
        BigDecimal sum = last20Ranges.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sma = sum.divide(BigDecimal.valueOf(last20Ranges.size()), SCALE, RoundingMode.HALF_UP);
        if (sma.signum() <= 0) {
            return new CompressionResult(BigDecimal.ZERO, threshold, false);
        }
        BigDecimal rci = prevDayRange.divide(sma, SCALE, RoundingMode.HALF_UP);
        return new CompressionResult(rci, threshold, rci.compareTo(threshold) < 0);
    }

    /**
     * Whether compression should VETO the selected trade.
     *
     * Compression only blocks ROTATION plays (range fades) — a coiled range is the
     * setup that fuels a breakout, so breakout/breakdown arms are never blocked by
     * compression; they benefit when the coil releases in their direction.
     *
     * @param primaryArm the selected arm (null for range-fade / no-trade)
     * @param rangeFade  true for a neutral range-fade (both rotation arms) — a rotation play
     */
    public boolean blocks(FutureArmType primaryArm, boolean rangeFade, CompressionResult result) {
        if (result == null || !result.compressed()) {
            return false;
        }
        if (rangeFade) {
            return true; // neutral range-fade is a rotation play → whipsaw risk
        }
        return primaryArm != null && primaryArm.isRotation();
    }
}
