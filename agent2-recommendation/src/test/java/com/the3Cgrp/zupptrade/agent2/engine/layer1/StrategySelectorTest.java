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
        // VIX=20 → IV=0.20; HV=0.13 → ratio=1.54 > 1.05 → RICH (Decision 2: VIX/100 as IV)
        ctx.setVix(new BigDecimal("20"));
        ctx.setHistoricalVolatility(new BigDecimal("0.13"));
        ctx.setOptionChainData(chain);
        return ctx;
    }
}
