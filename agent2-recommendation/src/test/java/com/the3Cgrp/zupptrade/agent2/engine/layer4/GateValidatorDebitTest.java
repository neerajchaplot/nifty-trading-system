package com.the3Cgrp.zupptrade.agent2.engine.layer4;

import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for debit-path gates (G1D, G2, G2D, G3D, G4D).
 *
 * Scenario: BULL_CALL_SPREAD at low VIX.
 *   ATM call (long) ltp=60, OTM call at ATM+200 (short) ltp=15.
 *   Net debit = 60 - 15 = 45 per unit.
 *   Spread width = 200 pts. Max profit = 200 - 45 = 155 per unit.
 *   R:R = 155 / 45 = 3.44 → G1D passes (≥ 3:1).
 *
 * Breakeven for call spread = longStrike + netDebit = 24000 + 45 = 24045.
 *   Distance from spot (24010) = 24045 - 24010 = 35 → G2D fails (> 30 pts).
 *   With spot at 24020: distance = 24045 - 24020 = 25 → G2D passes (≤ 30 pts).
 */
class GateValidatorDebitTest {

    private GateValidator gateValidator;
    private TradingConfig config;

    private static final BigDecimal SPOT_NEAR = new BigDecimal("24020");   // breakeven distance = 25 → G2D pass
    private static final BigDecimal SPOT_FAR  = new BigDecimal("23900");   // breakeven distance = 145 → G2D fail
    private static final int LONG_STRIKE = 24000;
    private static final int SHORT_STRIKE = 24200;  // ATM + 200
    private static final BigDecimal LONG_LTP  = new BigDecimal("60");
    private static final BigDecimal SHORT_LTP = new BigDecimal("15");

    @BeforeEach
    void setUp() {
        config = new TradingConfig();
        gateValidator = new GateValidator(config);
    }

    // ── G1D: R:R ≥ 3:1 ───────────────────────────────────────────────────────

    @Test
    void g1d_rrAboveThreshold_passes() {
        // netDebit=45, spreadWidth=200, maxProfit=155, R:R=3.44 ≥ 3.0 → passes
        RecommendationContext ctx = buildDebitContext(SPOT_NEAR, Confidence.MEDIUM, LONG_LTP, SHORT_LTP);
        gateValidator.execute(ctx);

        GateResultDto g1d = findGate(ctx, "G1D");
        assertThat(g1d.passed()).isTrue();
        assertThat(g1d.value()).isGreaterThanOrEqualTo(new BigDecimal("3.00"));
    }

    @Test
    void g1d_rrBelowThreshold_fails() {
        // netDebit=100, spreadWidth=200, maxProfit=100, R:R=1.0 < 3.0 → fails
        BigDecimal highDebit  = new BigDecimal("110");
        BigDecimal lowCredit  = new BigDecimal("10");
        RecommendationContext ctx = buildDebitContext(SPOT_NEAR, Confidence.MEDIUM, highDebit, lowCredit);
        gateValidator.execute(ctx);

        GateResultDto g1d = findGate(ctx, "G1D");
        assertThat(g1d.passed()).isFalse();
    }

    // ── G2: always informational ──────────────────────────────────────────────

    @Test
    void g2_isAlwaysInformationalForDebit() {
        RecommendationContext ctx = buildDebitContext(SPOT_NEAR, Confidence.MEDIUM, LONG_LTP, SHORT_LTP);
        gateValidator.execute(ctx);

        GateResultDto g2 = findGate(ctx, "G2");
        assertThat(g2.passed()).isTrue();
        assertThat(g2.value()).isEqualByComparingTo(new BigDecimal("45")); // net debit = 60 - 15
    }

    // ── G2D: breakeven ≤ 30 pts from spot ────────────────────────────────────

    @Test
    void g2d_breakevenWithinLimit_passes() {
        // breakeven = 24000 + 45 = 24045; spot = 24020; distance = 25 ≤ 30 → passes
        RecommendationContext ctx = buildDebitContext(SPOT_NEAR, Confidence.MEDIUM, LONG_LTP, SHORT_LTP);
        gateValidator.execute(ctx);

        GateResultDto g2d = findGate(ctx, "G2D");
        assertThat(g2d.passed()).isTrue();
        assertThat(g2d.value()).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    void g2d_breakevenTooFar_fails() {
        // breakeven = 24045; spot = 23900; distance = 145 > 30 → fails
        RecommendationContext ctx = buildDebitContext(SPOT_FAR, Confidence.MEDIUM, LONG_LTP, SHORT_LTP);
        gateValidator.execute(ctx);

        GateResultDto g2d = findGate(ctx, "G2D");
        assertThat(g2d.passed()).isFalse();
        assertThat(g2d.value()).isGreaterThan(new BigDecimal("30"));
    }

    // ── G3D: confidence ≥ MEDIUM ──────────────────────────────────────────────

    @Test
    void g3d_confidenceMedium_passes() {
        RecommendationContext ctx = buildDebitContext(SPOT_NEAR, Confidence.MEDIUM, LONG_LTP, SHORT_LTP);
        gateValidator.execute(ctx);

        GateResultDto g3d = findGate(ctx, "G3D");
        assertThat(g3d.passed()).isTrue();
    }

    @Test
    void g3d_confidenceHigh_passes() {
        RecommendationContext ctx = buildDebitContext(SPOT_NEAR, Confidence.HIGH, LONG_LTP, SHORT_LTP);
        gateValidator.execute(ctx);

        GateResultDto g3d = findGate(ctx, "G3D");
        assertThat(g3d.passed()).isTrue();
    }

    @Test
    void g3d_confidenceLow_fails() {
        // LOW confidence flows through Layer 1 on debit path, then rejected here
        RecommendationContext ctx = buildDebitContext(SPOT_NEAR, Confidence.LOW, LONG_LTP, SHORT_LTP);
        gateValidator.execute(ctx);

        GateResultDto g3d = findGate(ctx, "G3D");
        assertThat(g3d.passed()).isFalse();
        assertThat(g3d.description()).contains("conviction");
    }

    // ── G4D: total max loss ≤ 0.5% of capital ────────────────────────────────

    @Test
    void g4d_totalDebitWithinBudget_passes() {
        // capital=500000, budget=0.5%=2500; 1 lot: 45 × 75 = 3375 > 2500
        // With lotSize=65: 45 × 65 = 2925 > 2500 → but lots=0, min=1 → still > 2500 → fails
        // Use lower netDebit to make it pass: netDebit=30, 1 lot = 30×65=1950 < 2500 → passes
        BigDecimal longLtp  = new BigDecimal("40");
        BigDecimal shortLtp = new BigDecimal("10");  // netDebit=30
        RecommendationContext ctx = buildDebitContext(SPOT_NEAR, Confidence.MEDIUM, longLtp, shortLtp);
        ctx.setLots(1);
        ctx.setLotSize(65);

        GateResultDto g4d = gateValidator.validateG4D(ctx);
        // maxLossTotal = 30 × 1 × 65 = 1950; threshold = 500000 × 0.5% = 2500 → passes
        assertThat(g4d.passed()).isTrue();
        assertThat(g4d.value()).isEqualByComparingTo(new BigDecimal("1950.00"));
    }

    @Test
    void g4d_singleLotExceedsBudget_fails() {
        // capital=500000, budget=2500; netDebit=45, lotSize=65: 45×1×65=2925 > 2500 → fails
        RecommendationContext ctx = buildDebitContext(SPOT_NEAR, Confidence.MEDIUM, LONG_LTP, SHORT_LTP);
        ctx.setLots(1);

        GateResultDto g4d = gateValidator.validateG4D(ctx);
        assertThat(g4d.passed()).isFalse();
        assertThat(g4d.value()).isEqualByComparingTo(new BigDecimal("2925.00")); // 45 × 1 × 65
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private RecommendationContext buildDebitContext(BigDecimal spot, Confidence confidence,
                                                    BigDecimal longLtp, BigDecimal shortLtp) {
        RecommendationContext ctx = new RecommendationContext();
        ctx.setStrategy(Strategy.BULL_CALL_SPREAD);
        ctx.setSpreadDirection(SpreadDirection.DEBIT);
        ctx.setSpot(spot);

        Agent1SignalEntity signal = new Agent1SignalEntity();
        signal.setConfidence(confidence);
        ctx.setSignal(signal);

        // Long ATM call (lower strike) — BUY leg; Short OTM call (higher strike) — SELL leg
        TradeLegDto longLeg = new TradeLegDto(OptionType.CE, LONG_STRIKE,
                longLtp, LegAction.BUY, new BigDecimal("0.50"), new BigDecimal("0.50"), null);
        TradeLegDto shortLeg = new TradeLegDto(OptionType.CE, SHORT_STRIKE,
                shortLtp, LegAction.SELL, new BigDecimal("0.20"), new BigDecimal("0.20"), null);

        ctx.setLongLeg(longLeg);
        ctx.setShortLeg(shortLeg);
        ctx.setLotSize(65);

        UserProfileEntity profile = new UserProfileEntity();
        profile.setCapital(new BigDecimal("500000"));
        profile.setMaxLossPct(new BigDecimal("1.5"));
        ctx.setUserProfile(profile);

        return ctx;
    }

    private GateResultDto findGate(RecommendationContext ctx, String gate) {
        return ctx.getGateResults().stream()
                .filter(g -> g.gate().equals(gate))
                .findFirst()
                .orElseThrow(() -> new AssertionError(gate + " gate result not found"));
    }
}
