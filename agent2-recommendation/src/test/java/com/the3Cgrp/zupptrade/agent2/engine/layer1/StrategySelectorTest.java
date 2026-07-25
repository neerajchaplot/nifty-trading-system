package com.the3Cgrp.zupptrade.agent2.engine.layer1;

import com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData;
import com.the3Cgrp.zupptrade.agent2.client.model.StrikeData;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.shared.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers every row of the revised Layer-1 decision matrix.
 *
 * Governing rule under test:
 *   IV regime decides BUY vs SELL (RICH → credit, CHEAP → debit, FAIR → credit + gate decides),
 *   bias decides which side, strength decides directional vs neutral (WEAK = Iron Condor logic),
 *   VIX EXTREME is the only HARD stand-aside, and thin-premium condors are SOFT skips
 *   (card produced, gates/user decide — never a hard reject at Layer 1).
 */
class StrategySelectorTest {

    private StrategySelector strategySelector;

    @BeforeEach
    void setUp() {
        strategySelector = new StrategySelector(new TradingConfig());
    }

    // ─── BULLISH ────────────────────────────────────────────────────────────────

    @Test
    void bullishExtreme_anyVixAnyIv_bullCallDebit() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.EXTREME, VixRegime.NORMAL, Confidence.HIGH, IvRegime.FAIR);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT);
    }

    @Test
    void bullishMild_lowVix_bullCallDebit() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.MILD, VixRegime.LOW, Confidence.HIGH, IvRegime.FAIR);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT);
    }

    @Test
    void bullishMild_normalVix_richIv_bullPutCredit() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.MILD, VixRegime.NORMAL, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT);
    }

    @Test
    void bullishMild_highVix_richIv_bullPutCredit() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.MILD, VixRegime.HIGH, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT);
    }

    /** Change ① — cheap IV + directional bullish → BUY (debit), no longer SKIP. */
    @Test
    void bullishMild_highVix_cheapIv_bullCallDebit() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.MILD, VixRegime.HIGH, Confidence.HIGH, IvRegime.CHEAP);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT);
    }

    /** Change ① — fair IV + directional bullish → keep credit, let RoC gate decide, no longer SKIP. */
    @Test
    void bullishMild_normalVix_fairIv_bullPutCredit() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.MILD, VixRegime.NORMAL, Confidence.HIGH, IvRegime.FAIR);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT);
    }

    /** Change ② — bullish-weak now mirrors bearish-weak → Iron Condor logic (was SKIP). */
    @Test
    void bullishWeak_normalVix_richIv_ironCondor() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.WEAK, VixRegime.NORMAL, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.IRON_CONDOR, SpreadDirection.CREDIT);
    }

    @Test
    void bullishWeak_highVix_richIv_wideIronCondor() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.WEAK, VixRegime.HIGH, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.WIDE_IRON_CONDOR, SpreadDirection.CREDIT);
    }

    /** Change ② — thin-premium condor (low VIX): SOFT skip, card still produced. */
    @Test
    void bullishWeak_lowVix_softSkipIronCondor() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.WEAK, VixRegime.LOW, Confidence.HIGH, IvRegime.CHEAP);
        strategySelector.execute(ctx);
        assertSoft(ctx, Strategy.IRON_CONDOR);
    }

    @Test
    void bullishWeak_normalVix_cheapIv_softSkipIronCondor() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.WEAK, VixRegime.NORMAL, Confidence.HIGH, IvRegime.CHEAP);
        strategySelector.execute(ctx);
        assertSoft(ctx, Strategy.IRON_CONDOR);
    }

    // ─── BEARISH ────────────────────────────────────────────────────────────────

    @Test
    void bearishExtreme_lowVix_bearPutDebit() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.EXTREME, VixRegime.LOW, Confidence.HIGH, IvRegime.FAIR);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BEAR_PUT_SPREAD, SpreadDirection.DEBIT);
    }

    @Test
    void bearishMild_lowVix_bearPutDebit() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.MILD, VixRegime.LOW, Confidence.HIGH, IvRegime.FAIR);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BEAR_PUT_SPREAD, SpreadDirection.DEBIT);
    }

    @Test
    void bearishMild_normalVix_richIv_bearCallCredit() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.MILD, VixRegime.NORMAL, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BEAR_CALL_SPREAD, SpreadDirection.CREDIT);
    }

    @Test
    void bearishExtreme_highVix_richIv_bearCallCredit() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.EXTREME, VixRegime.HIGH, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BEAR_CALL_SPREAD, SpreadDirection.CREDIT);
    }

    /** Change ③ — cheap IV + directional bearish → BUY puts (debit), no longer SKIP.
     *  This also fixes the old bug where MILD-bearish+cheap was sent to sell thin BEAR_CALL. */
    @Test
    void bearishMild_highVix_cheapIv_bearPutDebit() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.MILD, VixRegime.HIGH, Confidence.HIGH, IvRegime.CHEAP);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BEAR_PUT_SPREAD, SpreadDirection.DEBIT);
    }

    /** Change ③ — fair IV + directional bearish → keep credit, let RoC gate decide, no longer SKIP. */
    @Test
    void bearishMild_normalVix_fairIv_bearCallCredit() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.MILD, VixRegime.NORMAL, Confidence.HIGH, IvRegime.FAIR);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BEAR_CALL_SPREAD, SpreadDirection.CREDIT);
    }

    @Test
    void bearishWeak_normalVix_richIv_ironCondor() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.WEAK, VixRegime.NORMAL, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.IRON_CONDOR, SpreadDirection.CREDIT);
    }

    @Test
    void bearishWeak_highVix_richIv_wideIronCondor() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.WEAK, VixRegime.HIGH, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.WIDE_IRON_CONDOR, SpreadDirection.CREDIT);
    }

    /** ④ kept — bearish-weak into thin premium: SOFT skip, gates decide. */
    @Test
    void bearishWeak_lowVix_softSkipIronCondor() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.WEAK, VixRegime.LOW, Confidence.HIGH, IvRegime.CHEAP);
        strategySelector.execute(ctx);
        assertSoft(ctx, Strategy.IRON_CONDOR);
    }

    @Test
    void bearishWeak_normalVix_fairIv_softSkipIronCondor() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.WEAK, VixRegime.NORMAL, Confidence.HIGH, IvRegime.FAIR);
        strategySelector.execute(ctx);
        assertSoft(ctx, Strategy.IRON_CONDOR);
    }

    // ─── NEUTRAL ────────────────────────────────────────────────────────────────

    @Test
    void neutralWeak_normalVix_richIv_ironCondor() {
        RecommendationContext ctx = ctx(Bias.NEUTRAL, Strength.WEAK, VixRegime.NORMAL, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.IRON_CONDOR, SpreadDirection.CREDIT);
    }

    @Test
    void neutralWeak_highVix_richIv_wideIronCondor() {
        RecommendationContext ctx = ctx(Bias.NEUTRAL, Strength.WEAK, VixRegime.HIGH, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.WIDE_IRON_CONDOR, SpreadDirection.CREDIT);
    }

    @Test
    void neutralWeak_lowVix_softSkipIronCondor() {
        RecommendationContext ctx = ctx(Bias.NEUTRAL, Strength.WEAK, VixRegime.LOW, Confidence.HIGH, IvRegime.CHEAP);
        strategySelector.execute(ctx);
        assertSoft(ctx, Strategy.IRON_CONDOR);
    }

    @Test
    void neutralWeak_normalVix_cheapIv_softSkipIronCondor() {
        RecommendationContext ctx = ctx(Bias.NEUTRAL, Strength.WEAK, VixRegime.NORMAL, Confidence.HIGH, IvRegime.CHEAP);
        strategySelector.execute(ctx);
        assertSoft(ctx, Strategy.IRON_CONDOR);
    }

    // ─── HARD stand-aside + confidence veto ──────────────────────────────────────

    /** The only HARD avoid: VIX EXTREME → skipDecision (→ REJECTED in production). */
    @Test
    void vixExtreme_hardSkip() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.MILD, VixRegime.EXTREME, Confidence.HIGH, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertThat(ctx.isSkipDecision()).isTrue();
        assertThat(ctx.isSoftSkip()).isFalse();
    }

    @Test
    void confidenceLow_bullishDirectionalCredit_hardSkip() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.MILD, VixRegime.HIGH, Confidence.LOW, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertThat(ctx.isSkipDecision()).isTrue();
    }

    @Test
    void confidenceLow_bearishDirectionalCredit_hardSkip() {
        RecommendationContext ctx = ctx(Bias.BEARISH, Strength.MILD, VixRegime.NORMAL, Confidence.LOW, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertThat(ctx.isSkipDecision()).isTrue();
    }

    /** Debit path is exempt from the confidence veto — Gate 3D handles LOW confidence later. */
    @Test
    void confidenceLow_debit_allowedThrough() {
        RecommendationContext ctx = ctx(Bias.BULLISH, Strength.MILD, VixRegime.LOW, Confidence.LOW, IvRegime.CHEAP);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT);
    }

    /** Iron Condor is non-directional — LOW confidence on direction is irrelevant, not vetoed. */
    @Test
    void confidenceLow_ironCondor_allowedThrough() {
        RecommendationContext ctx = ctx(Bias.NEUTRAL, Strength.WEAK, VixRegime.NORMAL, Confidence.LOW, IvRegime.RICH);
        strategySelector.execute(ctx);
        assertClean(ctx, Strategy.IRON_CONDOR, SpreadDirection.CREDIT);
    }

    // ─── Assertions ──────────────────────────────────────────────────────────────

    /** A full-conviction, tradeable selection: no hard skip, no soft skip. */
    private void assertClean(RecommendationContext ctx, Strategy strategy, SpreadDirection direction) {
        assertThat(ctx.getStrategy()).isEqualTo(strategy);
        assertThat(ctx.getSpreadDirection()).isEqualTo(direction);
        assertThat(ctx.isSkipDecision()).isFalse();
        assertThat(ctx.isSoftSkip()).isFalse();
    }

    /** A low-conviction fallback: strategy still produced, flagged soft, NOT a hard skip. */
    private void assertSoft(RecommendationContext ctx, Strategy strategy) {
        assertThat(ctx.getStrategy()).isEqualTo(strategy);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
        assertThat(ctx.isSoftSkip()).isTrue();
        assertThat(ctx.isSkipDecision()).isFalse();
        assertThat(ctx.getSoftSkipReason()).isNotBlank();
    }

    // ─── Fixture ─────────────────────────────────────────────────────────────────

    /**
     * Builds a context with a precise IV regime. IV regime = ATM call IV / HV, with HV fixed at 0.13
     * and rich/cheap thresholds 1.05 / 0.85 (TradingConfig defaults):
     *   RICH  → IV 0.159 (ratio 1.22),  FAIR → IV 0.130 (ratio 1.00),  CHEAP → IV 0.100 (ratio 0.77).
     * VIX regime is taken directly from the signal (StrategySelector reads signal.getVixRegime()).
     */
    private RecommendationContext ctx(Bias bias, Strength strength, VixRegime vixRegime,
                                      Confidence confidence, IvRegime ivRegime) {
        Agent1SignalEntity signal = new Agent1SignalEntity();
        signal.setBias(bias);
        signal.setStrength(strength);
        signal.setVixRegime(vixRegime);
        signal.setConfidence(confidence);

        BigDecimal atmIv = switch (ivRegime) {
            case RICH  -> new BigDecimal("0.159");
            case FAIR  -> new BigDecimal("0.130");
            case CHEAP -> new BigDecimal("0.100");
        };

        StrikeData atmCall = new StrikeData(24000, OptionType.CE, new BigDecimal("150"),
                atmIv, new BigDecimal("0.50"), new BigDecimal("0.50"),
                BigDecimal.valueOf(100000), new BigDecimal("149"), new BigDecimal("151"), null);
        StrikeData atmPut = new StrikeData(24000, OptionType.PE, new BigDecimal("148"),
                atmIv, new BigDecimal("-0.50"), new BigDecimal("0.50"),
                BigDecimal.valueOf(100000), new BigDecimal("147"), new BigDecimal("149"), null);

        OptionChainData chain = new OptionChainData(
                new BigDecimal("24000"), LocalDate.now().plusDays(5),
                List.of(atmCall), List.of(atmPut),
                24000, new BigDecimal("150"), new BigDecimal("148"));

        RecommendationContext ctx = new RecommendationContext();
        ctx.setSignal(signal);
        ctx.setVix(new BigDecimal("15"));                 // only a fallback if ATM IV were null
        ctx.setHistoricalVolatility(new BigDecimal("0.13"));
        ctx.setOptionChainData(chain);
        return ctx;
    }
}
