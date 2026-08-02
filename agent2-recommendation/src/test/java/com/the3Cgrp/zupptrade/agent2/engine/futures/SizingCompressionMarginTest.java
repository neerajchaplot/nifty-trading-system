package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.CompressionResult;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.MarginEstimate;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.SizingResult;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Position sizing (§6.5, lot size 65), compression gate (§6.3), and margin estimate (§6.8).
 */
class SizingCompressionMarginTest {

    private final PositionSizer sizer = new PositionSizer();
    private final CompressionGate compression = new CompressionGate();
    private final MarginEstimator margin = new MarginEstimator();

    @Test
    void sizing_lotsFromRiskCapitalAndStopPoints_lotSize65() {
        // riskCapital 7500, stopPoints 40, lot 65 → riskPerLot 2600 → lots floor(7500/2600)=2
        SizingResult s = sizer.size(new BigDecimal("7500"), new BigDecimal("40"), 65);
        assertThat(s.lots()).isEqualTo(2);
        assertThat(s.lotSize()).isEqualTo(65);
        assertThat(s.riskPerLot()).isEqualByComparingTo("2600");
        assertThat(s.riskTotal()).isEqualByComparingTo("5200");
        assertThat(s.pointValuePerLot()).isEqualByComparingTo("65");
    }

    @Test
    void sizing_arm1WorkedExampleStop_43points() {
        // Arm 1 stop distance |24280-24237| = 43 pts, lot 65 → riskPerLot 2795 → floor(7500/2795)=2
        SizingResult s = sizer.size(new BigDecimal("7500"), new BigDecimal("43"), 65);
        assertThat(s.lots()).isEqualTo(2);
        assertThat(s.riskPerLot()).isEqualByComparingTo("2795");
    }

    @Test
    void sizing_zeroStop_yieldsZeroLots_noDivideByZero() {
        SizingResult s = sizer.size(new BigDecimal("7500"), BigDecimal.ZERO, 65);
        assertThat(s.lots()).isZero();
    }

    @Test
    void compression_prevRangeSmallVsAverage_flagsCompressed() {
        // prev range 50 vs 20-day SMA 100 → RCI 0.5 < 0.70 threshold → compressed → NO_TRADE
        List<BigDecimal> avg100 = Collections.nCopies(20, new BigDecimal("100"));
        CompressionResult r = compression.evaluate(new BigDecimal("50"), avg100, new BigDecimal("0.70"));
        assertThat(r.rci()).isEqualByComparingTo("0.500");
        assertThat(r.compressed()).isTrue();
    }

    @Test
    void compression_normalRange_notCompressed() {
        List<BigDecimal> avg100 = Collections.nCopies(20, new BigDecimal("100"));
        CompressionResult r = compression.evaluate(new BigDecimal("120"), avg100, new BigDecimal("0.70"));
        assertThat(r.rci()).isEqualByComparingTo("1.200");
        assertThat(r.compressed()).isFalse();
    }

    @Test
    void compression_missingHistory_doesNotBlock() {
        CompressionResult r = compression.evaluate(new BigDecimal("100"), List.of(), new BigDecimal("0.70"));
        assertThat(r.compressed()).isFalse();
    }

    @Test
    void compressionVeto_blocksRotationButNotBreakout() {
        List<BigDecimal> avg100 = Collections.nCopies(20, new BigDecimal("100"));
        CompressionResult compressed = compression.evaluate(new BigDecimal("50"), avg100, new BigDecimal("0.70"));
        assertThat(compressed.compressed()).isTrue();

        // Rotation plays (and neutral range-fade) are vetoed by compression...
        assertThat(compression.blocks(FutureArmType.LONG_ROTATION, false, compressed)).isTrue();
        assertThat(compression.blocks(FutureArmType.SHORT_ROTATION, false, compressed)).isTrue();
        assertThat(compression.blocks(null, true, compressed)).isTrue();

        // ...but breakout/breakdown plays are NOT — a coil fuels the release.
        assertThat(compression.blocks(FutureArmType.LONG_BREAKOUT, false, compressed)).isFalse();
        assertThat(compression.blocks(FutureArmType.SHORT_BREAKDOWN, false, compressed)).isFalse();
    }

    @Test
    void compressionVeto_neverBlocksWhenNotCompressed() {
        List<BigDecimal> avg100 = Collections.nCopies(20, new BigDecimal("100"));
        CompressionResult normal = compression.evaluate(new BigDecimal("120"), avg100, new BigDecimal("0.70"));
        assertThat(compression.blocks(FutureArmType.LONG_ROTATION, false, normal)).isFalse();
    }

    @Test
    void margin_estimateIsPctOfNotional() {
        // entry 24280, lot 65, 2 lots → notional 3,156,400 ; 12% → 378,768
        MarginEstimate m = margin.estimate(new BigDecimal("24280"), 65, 2, new BigDecimal("0.12"));
        assertThat(m.notional()).isEqualByComparingTo("3156400");
        assertThat(m.marginEstimate().doubleValue()).isCloseTo(378768.0, within(0.5));
    }
}
