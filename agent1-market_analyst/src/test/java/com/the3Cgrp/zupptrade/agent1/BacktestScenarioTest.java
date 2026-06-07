package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.agent1.composer.SignalComposer;
import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.PrecomputedIndicators;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import com.the3Cgrp.zupptrade.agent1.scoring.CommentarySentimentScorer;
import com.the3Cgrp.zupptrade.agent1.scoring.InstitutionalFlowScorer;
import com.the3Cgrp.zupptrade.agent1.scoring.PriceStructureScorer;
import com.the3Cgrp.zupptrade.agent1.scoring.TechnicalScorer;
import com.the3Cgrp.zupptrade.agent1.scoring.TierScorer;
import com.the3Cgrp.zupptrade.agent1.scoring.VolatilityMacroScorer;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end scoring for the 15 May 2026 backtest scenario documented in CLAUDE.md §18.
 *
 * Inputs: spot=23412.60, 20EMA=23900, 50EMA=23690, PCR=1.17,
 *         FII long ratio=0.11, DII net=684Cr, VIX=18.61 (HIGH regime),
 *         VIX prev=19.43, Gift Nifty +70pts, Marketaux sentiment=-0.335
 *
 * Expected output: Bias=NEUTRAL, Strength=WEAK, Confidence=LOW
 *
 * Computed composite (with available inputs):
 *   Tier1A: avg=-2/7=-0.2857, contrib=-0.0857  (spot below both EMAs; others null)
 *   Tier1B: avg=1/5=+0.2000, contrib=+0.0400  (EMA20 > EMA50)
 *   Tier2:  avg=0/4=0.0000,  contrib=0.0000   (FII ratio bearish, DII bullish — net 0)
 *   Tier3:  avg=2/3=+0.6667, contrib=+0.0667  (VIX fell 4.2%, Gift Nifty +70)
 *   Tier4:  avg=-1/2=-0.5000, contrib=-0.0500  (Marketaux -0.335 → bearish)
 *   Composite ≈ -0.029 → NEUTRAL WEAK
 *   Confidence: base=1/5=0.20, VIX HIGH ×0.85 → 0.17 → LOW
 */
class BacktestScenarioTest {

    private TradingProperties props;
    private List<TierScorer> scorers;
    private SignalComposer composer;

    @BeforeEach
    void setUp() {
        props = new TradingProperties();
        scorers = List.of(
                new PriceStructureScorer(props),
                new TechnicalScorer(props),
                new InstitutionalFlowScorer(props),
                new VolatilityMacroScorer(props),
                new CommentarySentimentScorer(props)
        );
        composer = new SignalComposer(props);
    }

    @Test
    void backtestScenario_15May2026_producesNeutralWeakLow() {
        // Build indicators with only the values confirmed in the backtest session.
        // ema200=null: insufficient history not specified, score 0 per missing-data rule.
        PrecomputedIndicators indicators = new PrecomputedIndicators(
                new BigDecimal("23900"),   // ema20
                new BigDecimal("23690"),   // ema50
                null,                      // ema200 — not provided
                null,                      // rsi14 — not provided
                null,                      // macdLine — not provided
                null,                      // macdSignal — not provided
                null,                      // adx14 — not provided
                false,                     // bullishCandlePattern
                false,                     // bearishCandlePattern
                null,                      // higherHighs — not provided
                null                       // higherLows — not provided
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("23412.60"))
                .pcr(new BigDecimal("1.17"))
                .vixLevel(new BigDecimal("18.61"))
                .vixPrevLevel(new BigDecimal("19.43"))
                .vixRegime(VixRegime.HIGH)    // 18.0 < 18.61 < 24.0
                .fiiLongRatio(new BigDecimal("0.11"))
                .diiNet(new BigDecimal("684"))
                .giftNiftyPremium(new BigDecimal("70"))
                .marketauxSentiment(new BigDecimal("-0.335"))
                .indicators(indicators)
                .expiryDate(LocalDate.of(2026, 5, 19))
                .build();

        List<TierScore> tierScores = scorers.stream()
                .map(s -> s.calculate(inputs))
                .toList();

        var entity = composer.compose(tierScores, inputs, LocalDateTime.now());

        assertThat(entity.getBias()).isEqualTo(Bias.NEUTRAL);
        assertThat(entity.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(entity.getConfidence()).isEqualTo(Confidence.LOW);

        // Composite must be in the NEUTRAL band (-0.10 to +0.10)
        assertThat(entity.getCompositeScore())
                .isGreaterThanOrEqualTo(new BigDecimal("-0.1000"))
                .isLessThanOrEqualTo(new BigDecimal("0.1000"));

        // VIX HIGH regime sets direction to Falling (18.61 < 19.43)
        assertThat(entity.getVixDirection()).isEqualTo("Falling");
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.HIGH);
    }

    @Test
    void extremeBullish_isCorrectlyClassified() {
        PrecomputedIndicators strongBullish = new PrecomputedIndicators(
                new BigDecimal("24000"),   // ema20 — below spot
                new BigDecimal("23500"),   // ema50 — below spot
                new BigDecimal("22000"),   // ema200 — below spot
                new BigDecimal("65"),      // rsi > 60
                new BigDecimal("50"),      // macdLine > signal
                new BigDecimal("30"),      // macdSignal
                new BigDecimal("32"),      // adx > 30 → strong
                true,                      // bullish candle
                false,
                true,                      // higherHighs
                true                       // higherLows
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24500"))
                .pcr(new BigDecimal("1.40"))           // > 1.20 → bullish
                .futuresPremium(new BigDecimal("30"))  // > 20 → bullish
                .vixLevel(new BigDecimal("11"))
                .vixPrevLevel(new BigDecimal("13"))    // VIX fell ~15% → bullish
                .vixRegime(VixRegime.LOW)
                .fiiNetFutures(new BigDecimal("700"))  // > 500 → bullish
                .fiiLongRatio(new BigDecimal("0.65"))  // > 0.60 → bullish
                .fiiNetOptions(new BigDecimal("600"))  // > 500 → bullish
                .diiNet(new BigDecimal("600"))         // > 500 → bullish
                .giftNiftyPremium(new BigDecimal("80"))// > 50 → bullish
                .marketauxSentiment(new BigDecimal("0.45")) // > 0.30 → bullish
                .indicators(strongBullish)
                .expiryDate(LocalDate.of(2026, 5, 19))
                .build();

        List<TierScore> tierScores = scorers.stream()
                .map(s -> s.calculate(inputs))
                .toList();

        var entity = composer.compose(tierScores, inputs, LocalDateTime.now());

        assertThat(entity.getBias()).isEqualTo(Bias.BULLISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.EXTREME);
        assertThat(entity.getCompositeScore()).isGreaterThan(new BigDecimal("0.50"));
    }

    @Test
    void extremeBearish_isCorrectlyClassified() {
        PrecomputedIndicators strongBearish = new PrecomputedIndicators(
                new BigDecimal("22000"),   // ema20 — above spot
                new BigDecimal("22500"),   // ema50 — above spot
                new BigDecimal("23000"),   // ema200 — above spot
                new BigDecimal("30"),      // rsi < 40
                new BigDecimal("10"),      // macdLine < signal
                new BigDecimal("20"),      // macdSignal
                new BigDecimal("35"),      // adx > 30 → strong trend
                false,
                true,                      // bearish candle
                false,                     // lowerHighs
                false                      // lowerLows
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("21500"))
                .pcr(new BigDecimal("0.60"))           // < 0.80 → bearish
                .futuresPremium(new BigDecimal("-30")) // discount > 20 → bearish
                .vixLevel(new BigDecimal("25"))
                .vixPrevLevel(new BigDecimal("22"))    // VIX rose ~13.6% → bearish
                .vixRegime(VixRegime.EXTREME)
                .fiiNetFutures(new BigDecimal("-700")) // > 500 sell → bearish
                .fiiLongRatio(new BigDecimal("0.25"))  // < 0.40 → bearish
                .fiiNetOptions(new BigDecimal("-600")) // put buyer → bearish
                .diiNet(new BigDecimal("-600"))        // selling → bearish
                .giftNiftyPremium(new BigDecimal("-80"))// discount > 50 → bearish
                .marketauxSentiment(new BigDecimal("-0.50")) // < -0.30 → bearish
                .indicators(strongBearish)
                .expiryDate(LocalDate.of(2026, 5, 19))
                .build();

        List<TierScore> tierScores = scorers.stream()
                .map(s -> s.calculate(inputs))
                .toList();

        var entity = composer.compose(tierScores, inputs, LocalDateTime.now());

        assertThat(entity.getBias()).isEqualTo(Bias.BEARISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.EXTREME);
        assertThat(entity.getCompositeScore()).isLessThan(new BigDecimal("-0.50"));
    }

    @Test
    void allInputsNull_returnsNeutralWeakLow() {
        MarketInputs inputs = MarketInputs.builder()
                .vixRegime(VixRegime.NORMAL)
                .expiryDate(LocalDate.of(2026, 5, 19))
                .build();

        List<TierScore> tierScores = scorers.stream()
                .map(s -> s.calculate(inputs))
                .toList();

        var entity = composer.compose(tierScores, inputs, LocalDateTime.now());

        assertThat(entity.getBias()).isEqualTo(Bias.NEUTRAL);
        assertThat(entity.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(entity.getCompositeScore()).isEqualByComparingTo("0.0000");
    }
}
