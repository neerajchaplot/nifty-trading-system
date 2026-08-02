package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.CamarillaLevels;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic Camarilla pivots from prior-day OHLC (spec §2.1).
 *
 * <pre>
 * range = H - L
 * pivot = (H + L + C) / 3
 * H3 = C + range*1.1/4 ; L3 = C - range*1.1/4
 * H4 = C + range*1.1/2 ; L4 = C - range*1.1/2
 * </pre>
 *
 * Pure function — no state, no I/O — so it is unit-tested directly against the
 * spec's published 31-Jul levels.
 */
@Component
public class CamarillaCalculator {

    private static final BigDecimal ELEVEN_TENTHS = new BigDecimal("1.1");
    private static final BigDecimal FOUR = new BigDecimal("4");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal THREE = new BigDecimal("3");
    private static final int SCALE = 2;

    public CamarillaLevels calculate(BigDecimal high, BigDecimal low, BigDecimal close) {
        BigDecimal range = high.subtract(low);
        BigDecimal pivot = high.add(low).add(close)
                .divide(THREE, SCALE, RoundingMode.HALF_UP);

        BigDecimal factor = range.multiply(ELEVEN_TENTHS);          // range * 1.1
        BigDecimal quarter = factor.divide(FOUR, SCALE, RoundingMode.HALF_UP);
        BigDecimal half = factor.divide(TWO, SCALE, RoundingMode.HALF_UP);

        return new CamarillaLevels(
                range.setScale(SCALE, RoundingMode.HALF_UP),
                pivot,
                close.add(quarter).setScale(SCALE, RoundingMode.HALF_UP),  // H3
                close.add(half).setScale(SCALE, RoundingMode.HALF_UP),     // H4
                close.subtract(quarter).setScale(SCALE, RoundingMode.HALF_UP), // L3
                close.subtract(half).setScale(SCALE, RoundingMode.HALF_UP)     // L4
        );
    }
}
