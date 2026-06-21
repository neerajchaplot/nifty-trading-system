package com.the3Cgrp.zupptrade.agent2.engine.layer4;

import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the relaxed G1 PoP gate introduced for automated readjustment re-entry.
 *
 * Standard sell-spread gate: PoP ≥ 80%.
 * Readjust relaxed gate:     PoP ≥ 65% (normal VIX) or 70% (stressed VIX).
 * The relaxedGate1PopPct field on RecommendationContext overrides the standard threshold.
 */
class GateValidatorRelaxedGateTest {

    private GateValidator gateValidator;

    @BeforeEach
    void setUp() {
        gateValidator = new GateValidator(new TradingConfig());
    }

    // ── PoP convention ────────────────────────────────────────────────────────
    // GateValidator.validateG1() computes: seller_pop = (1 - shortLeg.pop()) × 100
    // shortLeg.pop() = Upstox buyer's PoP (P option expires ITM, 0-1 scale)
    // For an OTM short put: buyer's PoP is low (e.g. 0.18 = 18%), seller's PoP is high (82%).
    // Test values use this convention: shortPop = 1 - (desired_seller_pop / 100).

    // ── Standard gate (no relaxed override) ─────────────────────────────────

    @Test
    void g1_standardGate_popAt78Pct_fails() {
        // seller's PoP = 78% < 80% threshold → fails
        // shortPop (buyer's) = 1 - 0.78 = 0.22
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.22"), null);
        gateValidator.execute(ctx);

        GateResultDto g1 = findG1(ctx);
        assertThat(g1.passed()).isFalse();
        assertThat(g1.description()).doesNotContain("READJUST");
    }

    @Test
    void g1_standardGate_popAt82Pct_passes() {
        // seller's PoP = 82% ≥ 80% threshold → passes
        // shortPop (buyer's) = 1 - 0.82 = 0.18
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.18"), null);
        gateValidator.execute(ctx);

        GateResultDto g1 = findG1(ctx);
        assertThat(g1.passed()).isTrue();
        assertThat(g1.description()).doesNotContain("READJUST");
    }

    // ── Relaxed gate (readjustment re-entry) ─────────────────────────────────

    @Test
    void g1_relaxedGate65_popAt70Pct_passes() {
        // seller's PoP = 70% — below standard 80%, but above relaxed 65% → passes
        // shortPop (buyer's) = 1 - 0.70 = 0.30
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.30"), new BigDecimal("65.0"));
        gateValidator.execute(ctx);

        GateResultDto g1 = findG1(ctx);
        assertThat(g1.passed()).isTrue();
        assertThat(g1.description()).contains("READJUST");
    }

    @Test
    void g1_relaxedGate65_popAt60Pct_fails() {
        // seller's PoP = 60% — below even the relaxed 65% floor → fails
        // shortPop (buyer's) = 1 - 0.60 = 0.40
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.40"), new BigDecimal("65.0"));
        gateValidator.execute(ctx);

        GateResultDto g1 = findG1(ctx);
        assertThat(g1.passed()).isFalse();
        assertThat(g1.description()).contains("READJUST");
    }

    @Test
    void g1_relaxedGate70_popAt68Pct_fails() {
        // Stressed VIX (>22): seller's PoP = 68% < 70% floor → fails
        // shortPop (buyer's) = 1 - 0.68 = 0.32
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.32"), new BigDecimal("70.0"));
        gateValidator.execute(ctx);

        GateResultDto g1 = findG1(ctx);
        assertThat(g1.passed()).isFalse();
        assertThat(g1.description()).contains("READJUST");
    }

    @Test
    void g1_relaxedGate70_popAt72Pct_passes() {
        // Stressed VIX: seller's PoP = 72% ≥ 70% floor → passes
        // shortPop (buyer's) = 1 - 0.72 = 0.28
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.28"), new BigDecimal("70.0"));
        gateValidator.execute(ctx);

        GateResultDto g1 = findG1(ctx);
        assertThat(g1.passed()).isTrue();
        assertThat(g1.description()).contains("READJUST");
    }

    @Test
    void g1_relaxedGate_thresholdReportedCorrectlyInResult() {
        // Gate result must expose the relaxed threshold (65.0), not the standard 80.
        // seller's PoP = 70% (above 65% floor) → passes; shortPop = 0.30
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.30"), new BigDecimal("65.0"));
        gateValidator.execute(ctx);

        GateResultDto g1 = findG1(ctx);
        assertThat(g1.threshold()).isEqualByComparingTo(new BigDecimal("65.0"));
        assertThat(g1.passed()).isTrue();
    }

    @Test
    void g1_relaxedGateNull_usesStandardThreshold() {
        // Null relaxedGate1PopPct → standard 80% threshold enforced.
        // seller's PoP = 78% < 80% → fails; shortPop = 0.22
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.22"), null);
        gateValidator.execute(ctx);

        GateResultDto g1 = findG1(ctx);
        assertThat(g1.threshold()).isEqualByComparingTo(new BigDecimal("80.0"));
        assertThat(g1.passed()).isFalse();
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private GateResultDto findG1(RecommendationContext ctx) {
        return ctx.getGateResults().stream()
                .filter(g -> g.gate().equals("G1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("G1 gate result not found"));
    }

    // shortBuyerPop = Upstox buyer's PoP (P option expires ITM, 0-1 scale).
    // Seller's PoP computed by GateValidator as: (1 - shortBuyerPop) × 100.
    private RecommendationContext buildCreditContext(BigDecimal shortBuyerPop, BigDecimal relaxedPopPct) {
        RecommendationContext ctx = new RecommendationContext();
        ctx.setSpreadDirection(SpreadDirection.CREDIT);
        ctx.setRelaxedGate1PopPct(relaxedPopPct);

        // longLeg buyer pop slightly below shortLeg (wider OTM → smaller buyer pop)
        BigDecimal longBuyerPop = shortBuyerPop.subtract(new BigDecimal("0.05")).max(BigDecimal.ZERO);

        TradeLegDto shortLeg = new TradeLegDto(OptionType.PE, 23500,
                new BigDecimal("45.00"), LegAction.SELL, new BigDecimal("-0.18"), shortBuyerPop, null);
        TradeLegDto longLeg = new TradeLegDto(OptionType.PE, 23450,
                new BigDecimal("30.00"), LegAction.BUY, new BigDecimal("-0.14"), longBuyerPop, null);

        ctx.setShortLeg(shortLeg);
        ctx.setLongLeg(longLeg);
        ctx.setLotSize(65);

        UserProfileEntity profile = new UserProfileEntity();
        profile.setCapital(new BigDecimal("500000"));
        profile.setMinPop(new BigDecimal("80"));
        profile.setMaxPopPoppGap(new BigDecimal("15"));
        profile.setMaxLossPct(new BigDecimal("1.5"));
        profile.setSpreadWidthMin(50);
        profile.setSpreadWidthMax(100);
        ctx.setUserProfile(profile);

        return ctx;
    }
}
