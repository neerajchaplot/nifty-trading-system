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

class StrategySelectorTest {

    private StrategySelector strategySelector;

    @BeforeEach
    void setUp() {
        strategySelector = new StrategySelector(new TradingConfig());
    }

    @Test
    void bullishMildHighVixRichIv_selectsBullPutSpread() {
        RecommendationContext ctx = buildContext(Bias.BULLISH, Strength.MILD, VixRegime.HIGH, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BULL_PUT_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
    }

    @Test
    void bullishExtreme_selectsBullCallSpread_regardlessOfVix() {
        RecommendationContext ctx = buildContext(Bias.BULLISH, Strength.EXTREME, VixRegime.LOW, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BULL_CALL_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.DEBIT);
    }

    @Test
    void bearishMildHighVixRichIv_selectsBearCallSpread() {
        RecommendationContext ctx = buildContext(Bias.BEARISH, Strength.MILD, VixRegime.HIGH, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BEAR_CALL_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
    }

    @Test
    void neutralWeakNormalVixRichIv_selectsIronCondor() {
        RecommendationContext ctx = buildContext(Bias.NEUTRAL, Strength.WEAK, VixRegime.NORMAL, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.IRON_CONDOR);
    }

    @Test
    void vixExtreme_returnsNoTrade() {
        RecommendationContext ctx = buildContext(Bias.BULLISH, Strength.MILD, VixRegime.EXTREME, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.NO_TRADE);
    }

    @Test
    void confidenceLow_returnsNoTrade() {
        RecommendationContext ctx = buildContext(Bias.BULLISH, Strength.MILD, VixRegime.HIGH, Confidence.LOW);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.NO_TRADE);
    }

    @Test
    void bullishWeakVix_returnsSkip() {
        RecommendationContext ctx = buildContext(Bias.BULLISH, Strength.WEAK, VixRegime.HIGH, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.SKIP);
    }

    // ─── Debit path tests ───────────────────────────────────────────────────────

    @Test
    void bullishMildLowVix_selectsBullCallSpreadDebit() {
        RecommendationContext ctx = buildContextLowVix(Bias.BULLISH, Strength.MILD, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BULL_CALL_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.DEBIT);
    }

    @Test
    void bullishExtremeLowVix_selectsBullCallSpreadDebit() {
        RecommendationContext ctx = buildContextLowVix(Bias.BULLISH, Strength.EXTREME, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BULL_CALL_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.DEBIT);
    }

    @Test
    void bearishMildLowVix_selectsBearPutSpreadDebit() {
        RecommendationContext ctx = buildContextLowVix(Bias.BEARISH, Strength.MILD, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BEAR_PUT_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.DEBIT);
    }

    @Test
    void bearishExtremeLowVix_selectsBearPutSpreadDebit() {
        RecommendationContext ctx = buildContextLowVix(Bias.BEARISH, Strength.EXTREME, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BEAR_PUT_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.DEBIT);
    }

    @Test
    void bullishMildLowVixConfidenceLow_debitPathAllowedThrough() {
        // LOW confidence is allowed to flow to Gate 3D on debit path — blocked at Layer 1 for credit only
        RecommendationContext ctx = buildContextLowVix(Bias.BULLISH, Strength.MILD, Confidence.LOW);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BULL_CALL_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.DEBIT);
    }

    @Test
    void bearishMildLowVixConfidenceLow_debitPathAllowedThrough() {
        RecommendationContext ctx = buildContextLowVix(Bias.BEARISH, Strength.MILD, Confidence.LOW);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BEAR_PUT_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.DEBIT);
    }

    @Test
    void neutralWeakHighVix_selectsWideIronCondor() {
        RecommendationContext ctx = buildContext(Bias.NEUTRAL, Strength.WEAK, VixRegime.HIGH, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.WIDE_IRON_CONDOR);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
    }

    @Test
    void bullishMildNormalVixRichIv_selectsBullPutSpread() {
        RecommendationContext ctx = buildContext(Bias.BULLISH, Strength.MILD, VixRegime.NORMAL, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BULL_PUT_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
    }

    @Test
    void bearishMildNormalVixRichIv_selectsBearCallSpread() {
        RecommendationContext ctx = buildContext(Bias.BEARISH, Strength.MILD, VixRegime.NORMAL, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BEAR_CALL_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
    }

    // ─── BEARISH+WEAK falls through to neutral (IronCondor) logic ───────────────

    @Test
    void bearishWeakNormalVixRichIv_selectsIronCondor() {
        // Today's scenario: BEARISH+WEAK+NORMAL+RICH → IronCondor (non-directional harvest)
        RecommendationContext ctx = buildContext(Bias.BEARISH, Strength.WEAK, VixRegime.NORMAL, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.IRON_CONDOR);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
    }

    @Test
    void bearishWeakHighVixRichIv_selectsWideIronCondor() {
        // High VIX + weak bearish → wider strikes to accommodate elevated moves
        RecommendationContext ctx = buildContext(Bias.BEARISH, Strength.WEAK, VixRegime.HIGH, Confidence.HIGH);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.WIDE_IRON_CONDOR);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
    }

    @Test
    void bearishWeakNormalVixConfidenceLow_ironCondorAllowedThrough() {
        // IronCondor is non-directional — LOW confidence on direction is irrelevant to its thesis.
        // Should NOT be blocked by the Confidence.LOW veto (which only applies to directional credit).
        RecommendationContext ctx = buildContext(Bias.BEARISH, Strength.WEAK, VixRegime.NORMAL, Confidence.LOW);
        strategySelector.execute(ctx);
        assertThat(ctx.getStrategy()).isEqualTo(Strategy.IRON_CONDOR);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private RecommendationContext buildContext(Bias bias, Strength strength, VixRegime vixRegime, Confidence confidence) {
        Agent1SignalEntity signal = new Agent1SignalEntity();
        signal.setBias(bias);
        signal.setStrength(strength);
        signal.setVixRegime(vixRegime);
        signal.setConfidence(confidence);

        StrikeData atmCall = new StrikeData(24000, OptionType.CE, new BigDecimal("150"),
                new BigDecimal("0.159"), new BigDecimal("0.50"), new BigDecimal("0.50"),
                BigDecimal.valueOf(100000), new BigDecimal("149"), new BigDecimal("151"), null);

        StrikeData atmPut = new StrikeData(24000, OptionType.PE, new BigDecimal("148"),
                new BigDecimal("0.159"), new BigDecimal("-0.50"), new BigDecimal("0.50"),
                BigDecimal.valueOf(100000), new BigDecimal("147"), new BigDecimal("149"), null);

        OptionChainData chain = new OptionChainData(
                new BigDecimal("24000"), LocalDate.now().plusDays(5),
                List.of(atmCall), List.of(atmPut),
                24000, new BigDecimal("150"), new BigDecimal("148")
        );

        RecommendationContext ctx = new RecommendationContext();
        ctx.setSignal(signal);
        // VIX=20 → chain IV=0.159; HV=0.13 → ratio=1.22 > 1.05 → RICH
        ctx.setVix(new BigDecimal("20"));
        ctx.setHistoricalVolatility(new BigDecimal("0.13"));
        ctx.setOptionChainData(chain);
        return ctx;
    }

    private RecommendationContext buildContextLowVix(Bias bias, Strength strength, Confidence confidence) {
        Agent1SignalEntity signal = new Agent1SignalEntity();
        signal.setBias(bias);
        signal.setStrength(strength);
        signal.setVixRegime(VixRegime.LOW);
        signal.setConfidence(confidence);

        // iv=null → resolveIv() falls back to VIX/100 = 0.11; HV=0.13 → CHEAP (irrelevant for debit path)
        StrikeData atmCall = new StrikeData(24000, OptionType.CE, new BigDecimal("60"),
                null, new BigDecimal("0.50"), new BigDecimal("0.50"),
                BigDecimal.valueOf(100000), new BigDecimal("59"), new BigDecimal("61"), null);

        StrikeData atmPut = new StrikeData(24000, OptionType.PE, new BigDecimal("58"),
                null, new BigDecimal("-0.50"), new BigDecimal("0.50"),
                BigDecimal.valueOf(100000), new BigDecimal("57"), new BigDecimal("59"), null);

        OptionChainData chain = new OptionChainData(
                new BigDecimal("24000"), LocalDate.now().plusDays(5),
                List.of(atmCall), List.of(atmPut),
                24000, new BigDecimal("60"), new BigDecimal("58")
        );

        RecommendationContext ctx = new RecommendationContext();
        ctx.setSignal(signal);
        ctx.setVix(new BigDecimal("11"));  // VIX < 13 → LOW regime
        ctx.setHistoricalVolatility(new BigDecimal("0.13"));
        ctx.setOptionChainData(chain);
        return ctx;
    }
}
