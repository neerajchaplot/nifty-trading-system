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

class GateValidatorTest {

    private GateValidator gateValidator;
    private TradingConfig config;

    @BeforeEach
    void setUp() {
        config = new TradingConfig();
        gateValidator = new GateValidator(config);
    }

    // ── G1 Tests ─────────────────────────────────────────────────────────────

    @Test
    void g1_sellSpread_popAboveThreshold_passes() {
        // shortLeg.pop = Upstox buyer's PoP (P option expires ITM) = 0.13 (OTM put)
        // seller's PoP = (1 - 0.13) × 100 = 87% ≥ 80% → passes
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.13"), new BigDecimal("0.10"));
        gateValidator.execute(ctx);

        GateResultDto g1 = ctx.getGateResults().stream().filter(g -> g.gate().equals("G1")).findFirst().orElseThrow();
        assertThat(g1.passed()).isTrue();
    }

    @Test
    void g1_sellSpread_popBelowThreshold_fails() {
        // shortLeg.pop = buyer's PoP = 0.25 → seller's PoP = 75% < 80% → fails
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.25"), new BigDecimal("0.20"));
        gateValidator.execute(ctx);

        GateResultDto g1 = ctx.getGateResults().stream().filter(g -> g.gate().equals("G1")).findFirst().orElseThrow();
        assertThat(g1.passed()).isFalse();
    }

    // ── G2 Tests ─────────────────────────────────────────────────────────────

    @Test
    void g2_isAlwaysIndicativeAndPasses() {
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.87"), new BigDecimal("0.91"));
        gateValidator.execute(ctx);

        GateResultDto g2 = ctx.getGateResults().stream().filter(g -> g.gate().equals("G2")).findFirst().orElseThrow();
        assertThat(g2.passed()).isTrue(); // G2 is always true — indicative only
    }

    // ── G3 Tests ─────────────────────────────────────────────────────────────

    @Test
    void g3_popPoppGapWithinLimit_passes() {
        // PoP = 87%, PoPP = 90% — gap = 3%, threshold = 15%
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.87"), new BigDecimal("0.90"));
        gateValidator.execute(ctx);

        GateResultDto g3 = ctx.getGateResults().stream().filter(g -> g.gate().equals("G3")).findFirst().orElseThrow();
        assertThat(g3.passed()).isTrue();
    }

    @Test
    void g3_popPoppGapExceedsLimit_fails() {
        // PoP = 82%, PoPP = 98% — gap = 16%, threshold = 15%
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.82"), new BigDecimal("0.98"));
        gateValidator.execute(ctx);

        GateResultDto g3 = ctx.getGateResults().stream().filter(g -> g.gate().equals("G3")).findFirst().orElseThrow();
        assertThat(g3.passed()).isFalse();
    }

    // ── G4 Tests ─────────────────────────────────────────────────────────────

    @Test
    void g4_rocAboveMinimum_passes() {
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.87"), new BigDecimal("0.90"));
        ctx.setDte(5);
        ctx.setRoc(new BigDecimal("0.55")); // min RoC for DTE=5 = 0.5% × (5/5) = 0.5%

        GateResultDto g4 = gateValidator.validateG4(ctx);
        assertThat(g4.passed()).isTrue();
    }

    @Test
    void g4_rocBelowMinimum_fails() {
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.87"), new BigDecimal("0.90"));
        ctx.setDte(5);
        ctx.setRoc(new BigDecimal("0.30")); // below 0.5% minimum

        GateResultDto g4 = gateValidator.validateG4(ctx);
        assertThat(g4.passed()).isFalse();
    }

    @Test
    void g4_dteTwoAdjustsThresholdDown() {
        RecommendationContext ctx = buildCreditContext(new BigDecimal("0.87"), new BigDecimal("0.90"));
        ctx.setDte(2);
        // min RoC for DTE=2 = 0.5% × (2/5) = 0.2%
        ctx.setRoc(new BigDecimal("0.25"));

        GateResultDto g4 = gateValidator.validateG4(ctx);
        assertThat(g4.passed()).isTrue();
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private RecommendationContext buildCreditContext(BigDecimal shortPop, BigDecimal longPop) {
        RecommendationContext ctx = new RecommendationContext();
        ctx.setSpreadDirection(SpreadDirection.CREDIT);

        TradeLegDto shortLeg = new TradeLegDto(OptionType.PE, 23500,
                new BigDecimal("34.50"), LegAction.SELL, new BigDecimal("-0.15"), shortPop, null);
        TradeLegDto longLeg = new TradeLegDto(OptionType.PE, 23450,
                new BigDecimal("29.40"), LegAction.BUY, new BigDecimal("-0.12"), longPop, null);

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
