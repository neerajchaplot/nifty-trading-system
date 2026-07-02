package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.agent1.composer.SignalComposer;
import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-pipeline scoring scenarios corresponding to the 12 SQL seed signals.
 *
 * Each test builds MarketInputs directly (no Spring, no DB, no external APIs)
 * and runs inputs → 5 TierScorers → SignalComposer → assert entity.
 *
 * Read the log output alongside test results to understand exactly which
 * signals fired and how composite + confidence were computed.
 *
 * MATH REFERENCE (defaults from TradingProperties):
 *   Weights:         T1A=0.30  T1B=0.20  T2=0.30  T3=0.10  T4=0.10
 *   VIX modifier:   LOW×1.10  NORMAL×1.00  HIGH×0.85  EXTREME×0.60
 *   ADX modifier:   >30 ×1.15 | 20–30 ×1.00 | <20 ×0.80 | null ×1.00
 *   Confidence:     HIGH >0.70  MEDIUM 0.41–0.70  LOW <0.41
 *   Divergence:     T4 opposes overall direction → ×0.80 penalty
 *
 *   PCR:   bullish >1.20 | bearish <0.80
 *   FII/DII flow: significant >500Cr or <−500Cr
 *   FII long ratio: bullish >0.60 | bearish <0.40
 *   Gift Nifty: bullish >50pts | bearish <−50pts
 *   VIX daily change: bullish if pct<−2% | bearish if pct>+10%
 *   Marketaux: bullish >0.30 | bearish <−0.30
 */
class BacktestScenarioTest {

    private static final Logger log = LoggerFactory.getLogger(BacktestScenarioTest.class);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 7, 7);

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

    // ─────────────────────────────────────────────────────────────────────────
    // EXISTING TESTS (preserved as-is)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * CLAUDE.md §18 backtest — 15 May 2026.
     * Composite ≈ −0.029 → NEUTRAL WEAK LOW.
     * Tiers: T1A bearish (spot below EMAs), T3 bullish (VIX falling, Gift+70),
     *        T4 bearish (Marketaux −0.335), T1B/T2 partial.
     */
    @Test
    void backtestScenario_15May2026_producesNeutralWeakLow() {
        PrecomputedIndicators indicators = new PrecomputedIndicators(
                new BigDecimal("23900"),   // ema20 — above spot
                new BigDecimal("23690"),   // ema50 — above spot
                null,                      // ema200 — not provided → 0
                null,                      // rsi14 — not provided → 0
                null,                      // macdLine — not provided
                null,                      // macdSignal — not provided
                null,                      // adx14 — not provided → moderate ×1.00
                false,
                false,
                null,                      // higherHighs — not provided → 0
                null
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("23412.60"))
                .pcr(new BigDecimal("1.17"))
                .vixLevel(new BigDecimal("18.61"))
                .vixPrevLevel(new BigDecimal("19.43"))
                .vixRegime(VixRegime.HIGH)
                .fiiLongRatio(new BigDecimal("0.11"))
                .diiNet(new BigDecimal("684"))
                .giftNiftyPremium(new BigDecimal("70"))
                .marketauxSentiment(new BigDecimal("-0.335"))
                .indicators(indicators)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "15-May-2026 backtest");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("15-May-2026 backtest → expected NEUTRAL WEAK LOW", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.NEUTRAL);
        assertThat(entity.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(entity.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(entity.getCompositeScore())
                .isGreaterThanOrEqualTo(new BigDecimal("-0.1000"))
                .isLessThanOrEqualTo(new BigDecimal("0.1000"));
        assertThat(entity.getVixDirection()).isEqualTo("Falling");
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.HIGH);
    }

    /**
     * All tiers strongly bullish, VIX LOW (×1.10), strong ADX (×1.15).
     * Confidence base=1.0 → capped at 1.0 → HIGH.
     */
    @Test
    void extremeBullish_isCorrectlyClassified() {
        PrecomputedIndicators strongBullish = new PrecomputedIndicators(
                new BigDecimal("24000"),
                new BigDecimal("23500"),
                new BigDecimal("22000"),
                new BigDecimal("65"),      // rsi >60
                new BigDecimal("50"),      // macdLine > signal
                new BigDecimal("30"),
                new BigDecimal("32"),      // adx >30 → strong trend ×1.15
                true,
                false,
                true,
                true
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24500"))
                .pcr(new BigDecimal("1.40"))
                .futuresPremium(new BigDecimal("30"))
                .vixLevel(new BigDecimal("11"))
                .vixPrevLevel(new BigDecimal("13"))
                .vixRegime(VixRegime.LOW)
                .fiiNetFutures(new BigDecimal("700"))
                .fiiLongRatio(new BigDecimal("0.65"))
                .fiiNetOptions(new BigDecimal("600"))
                .diiNet(new BigDecimal("600"))
                .giftNiftyPremium(new BigDecimal("80"))
                .marketauxSentiment(new BigDecimal("0.45"))
                .indicators(strongBullish)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "extreme bullish / VIX LOW");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("extreme bullish → expected BULLISH EXTREME HIGH", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.BULLISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.EXTREME);
        assertThat(entity.getCompositeScore()).isGreaterThan(new BigDecimal("0.50"));
    }

    /**
     * All tiers strongly bearish, VIX EXTREME (×0.60).
     * Composite < −0.50 → BEARISH EXTREME.
     */
    @Test
    void extremeBearish_isCorrectlyClassified() {
        PrecomputedIndicators strongBearish = new PrecomputedIndicators(
                new BigDecimal("22000"),
                new BigDecimal("22500"),
                new BigDecimal("23000"),
                new BigDecimal("30"),      // rsi <40
                new BigDecimal("10"),      // macdLine < signal
                new BigDecimal("20"),
                new BigDecimal("35"),      // strong trend
                false,
                true,
                false,
                false
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("21500"))
                .pcr(new BigDecimal("0.60"))
                .futuresPremium(new BigDecimal("-30"))
                .vixLevel(new BigDecimal("25"))
                .vixPrevLevel(new BigDecimal("22"))
                .vixRegime(VixRegime.EXTREME)
                .fiiNetFutures(new BigDecimal("-700"))
                .fiiLongRatio(new BigDecimal("0.25"))
                .fiiNetOptions(new BigDecimal("-600"))
                .diiNet(new BigDecimal("-600"))
                .giftNiftyPremium(new BigDecimal("-80"))
                .marketauxSentiment(new BigDecimal("-0.50"))
                .indicators(strongBearish)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "extreme bearish / VIX EXTREME");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("extreme bearish → expected BEARISH EXTREME", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.BEARISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.EXTREME);
        assertThat(entity.getCompositeScore()).isLessThan(new BigDecimal("-0.50"));
    }

    /** Edge case: all inputs null — every signal scores 0, composite = 0.0000. */
    @Test
    void allInputsNull_returnsNeutralWeakLow() {
        MarketInputs inputs = MarketInputs.builder()
                .vixRegime(VixRegime.NORMAL)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "all inputs null");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("all null → expected NEUTRAL WEAK LOW", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.NEUTRAL);
        assertThat(entity.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(entity.getCompositeScore()).isEqualByComparingTo("0.0000");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW SCENARIO TESTS — S01 through S12
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * S01 — BULLISH EXTREME | VIX NORMAL | HIGH confidence.
     *
     * All 5 tiers strongly bullish. VIX NORMAL (×1.00), no ADX (×1.00).
     * base=5/5=1.0 → confidence=1.0 capped → HIGH.
     *
     * T1A  [spot>ema20(+1) spot>ema50(+1) spot>ema200(+1) HH+HL(+1) premium(+1) pcr(+1) maxPain(0)]  = 6/7 = 0.857  → ×0.30 = +0.257
     * T1B  [ema20>ema50(+1) ema50>ema200(+1) rsi>60(+1) macd(+1) candle(+1)]                         = 5/5 = 1.000  → ×0.20 = +0.200
     * T2   [fiiNet(+1) fiiRatio(+1) fiiOpt(+1) dii(+1)]                                              = 4/4 = 1.000  → ×0.30 = +0.300
     * T3   [vixFall(+1) putOI>callOI(+1) giftNifty(+1)]                                              = 3/3 = 1.000  → ×0.10 = +0.100
     * T4   [marketaux(+1) commentary(+1)]                                                             = 2/2 = 1.000  → ×0.10 = +0.100
     * composite ≈ 0.957 → BULLISH EXTREME
     * confidence = 5/5 × 1.00 × 1.00 = 1.00 capped → HIGH
     */
    @Test
    void s01_bullishExtreme_vixNormal_allTiersBullish_producesHighConfidence() {
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("24000"),   // ema20 < spot → +1
                new BigDecimal("23500"),   // ema50 < spot → +1
                new BigDecimal("22000"),   // ema200 < spot → +1
                new BigDecimal("65"),      // rsi > 60 → +1
                new BigDecimal("50"),      // macdLine > macdSignal → +1
                new BigDecimal("30"),      // macdSignal
                null,                      // adx null → moderate modifier ×1.00
                true,                      // bullish candle → +1
                false,
                true,                      // higherHighs → +1
                true                       // higherLows  → +1
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24500"))
                .futuresPremium(new BigDecimal("30"))   // >20 → +1
                .pcr(new BigDecimal("1.40"))             // >1.20 → +1
                .vixLevel(new BigDecimal("15.20"))
                .vixPrevLevel(new BigDecimal("16.00"))  // VIX falling → +1 in T3
                .vixRegime(VixRegime.NORMAL)
                .fiiNetFutures(new BigDecimal("700"))   // >500 → +1
                .fiiLongRatio(new BigDecimal("0.65"))   // >0.60 → +1
                .fiiNetOptions(new BigDecimal("600"))   // >500 → +1
                .diiNet(new BigDecimal("600"))          // >500 → +1
                .callOiChange(new BigDecimal("100"))
                .putOiChange(new BigDecimal("300"))     // put>call → +1 in T3
                .giftNiftyPremium(new BigDecimal("80")) // >50 → +1
                .marketauxSentiment(new BigDecimal("0.45"))  // >0.30 → +1
                .commentaryBias("BULLISH")              // → +1
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S01 — BULLISH EXTREME VIX NORMAL");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S01 → expected BULLISH EXTREME HIGH", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.BULLISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.EXTREME);
        assertThat(entity.getCompositeScore()).isGreaterThan(new BigDecimal("0.50"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.NORMAL);
        assertThat(entity.getCommentaryDivergence()).isFalse();
    }

    /**
     * S02 — BULLISH MILD | VIX HIGH | MEDIUM confidence.
     *
     * 3 tiers agree with BULLISH (T1A, T1B, T4). VIX HIGH applies ×0.85.
     * base=3/5=0.60 → 0.60×0.85 = 0.51 → MEDIUM.
     *
     * T1A  [spot>ema20(+1) spot>ema50(+1) spot>ema200(+1) HH+HL(+1) premium(0) pcr(0) maxPain(0)]  = 4/7 = 0.571 → ×0.30 = +0.171
     * T1B  [ema20>ema50(+1) ema50>ema200(+1) rsi=55(0) macd(+1) candle(0)]                         = 3/5 = 0.600 → ×0.20 = +0.120
     * T2   [fiiNet=420(0) fiiRatio=0.55(0) fiiOpt=380(0) dii=380(0)]                               = 0/4 = 0.000 → ×0.30 =  0.000
     * T3   [vixChange=-1.4%(0) oi(0) giftNifty=40(0)]                                              = 0/3 = 0.000 → ×0.10 =  0.000
     * T4   [marketaux=0.20(0) commentary=BULLISH(+1)]                                              = 1/2 = 0.500 → ×0.10 = +0.050
     * composite ≈ 0.341 → BULLISH MILD
     * confidence = 3/5 × 0.85 × 1.00 = 0.51 → MEDIUM
     */
    @Test
    void s02_bullishMild_vixHigh_threeTiersBullish_producesMediumConfidence() {
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("23900"),   // ema20 < spot → +1
                new BigDecimal("23650"),   // ema50 < spot → +1
                new BigDecimal("23100"),   // ema200 < spot → +1
                new BigDecimal("55"),      // rsi 40–60 → 0 neutral
                new BigDecimal("25"),      // macdLine > macdSignal → +1
                new BigDecimal("20"),
                null,                      // adx null → ×1.00
                false,
                false,
                true,                      // higherHighs → +1
                true                       // higherLows  → +1
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24120"))
                .futuresPremium(new BigDecimal("15"))    // <20 → 0 neutral
                .pcr(new BigDecimal("1.10"))              // 0.80–1.20 → 0 neutral
                .vixLevel(new BigDecimal("20.50"))
                .vixPrevLevel(new BigDecimal("20.80"))   // pct change = −1.4% (> −2%) → 0
                .vixRegime(VixRegime.HIGH)
                .fiiNetFutures(new BigDecimal("420"))    // <500 → 0
                .fiiLongRatio(new BigDecimal("0.55"))    // 0.40–0.60 → 0
                .fiiNetOptions(new BigDecimal("380"))    // <500 → 0
                .diiNet(new BigDecimal("380"))           // <500 → 0
                .giftNiftyPremium(new BigDecimal("40"))  // <50 → 0
                .marketauxSentiment(new BigDecimal("0.20"))  // −0.30 to 0.30 → 0
                .commentaryBias("BULLISH")               // → +1
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S02 — BULLISH MILD VIX HIGH");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S02 → expected BULLISH MILD MEDIUM", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.BULLISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.MILD);
        assertThat(entity.getCompositeScore())
                .isGreaterThan(new BigDecimal("0.25"))
                .isLessThan(new BigDecimal("0.50"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.HIGH);
        assertThat(entity.getCommentaryDivergence()).isFalse();
    }

    /**
     * S03 — BULLISH MILD | VIX NORMAL | MEDIUM confidence.
     *
     * 3 tiers agree with BULLISH (T1A, T1B, T3). VIX NORMAL ×1.00.
     * base=3/5=0.60 → 0.60×1.00 = 0.60 → MEDIUM.
     * Key differentiator vs S02: VIX NORMAL (not HIGH) — confidence not penalised.
     * Agent 2 picks BullPutSpread for both; this test exercises the VIX Normal + IV Fair path.
     *
     * T1A  [spot>ema20(+1) spot>ema50(+1) spot>ema200(+1) HH+HL(+1) premium=18(0) pcr=1.05(0) maxPain(0)]  = 4/7 = 0.571 → +0.171
     * T1B  [ema20>ema50(+1) ema50>ema200(+1) rsi=52(0) macd(+1) candle(0)]                                 = 3/5 = 0.600 → +0.120
     * T2   [fiiNet=300(0) fiiRatio=0.48(0) fiiOpt=200(0) dii=250(0)]                                       = 0/4 = 0.000 →  0.000
     * T3   [vixChange=−2.47%(+1) oi(0) giftNifty=30(0)]                                                    = 1/3 = 0.333 → +0.033
     * T4   [marketaux=0.15(0) commentary=NEUTRAL(0)]                                                        = 0/2 = 0.000 →  0.000
     * composite ≈ 0.324 → BULLISH MILD
     * confidence = 3/5 × 1.00 × 1.00 = 0.60 → MEDIUM
     */
    @Test
    void s03_bullishMild_vixNormal_threeTiersBullish_producesMediumConfidence() {
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("23800"),   // ema20 < spot → +1
                new BigDecimal("23600"),   // ema50 < spot → +1
                new BigDecimal("23200"),   // ema200 < spot → +1
                new BigDecimal("52"),      // rsi 40–60 → 0
                new BigDecimal("15"),      // macdLine > macdSignal → +1
                new BigDecimal("10"),
                null,
                false,
                false,
                true,                      // higherHighs → +1
                true                       // higherLows  → +1
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24050"))
                .futuresPremium(new BigDecimal("18"))    // <20 → 0
                .pcr(new BigDecimal("1.05"))              // neutral → 0
                .vixLevel(new BigDecimal("15.80"))
                .vixPrevLevel(new BigDecimal("16.20"))   // pct change = −2.47% < −2% → +1 in T3
                .vixRegime(VixRegime.NORMAL)
                .fiiNetFutures(new BigDecimal("300"))    // <500 → 0
                .fiiLongRatio(new BigDecimal("0.48"))    // neutral → 0
                .fiiNetOptions(new BigDecimal("200"))    // <500 → 0
                .diiNet(new BigDecimal("250"))           // <500 → 0
                .giftNiftyPremium(new BigDecimal("30"))  // <50 → 0
                .marketauxSentiment(new BigDecimal("0.15"))  // neutral → 0
                .commentaryBias("NEUTRAL")               // → 0
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S03 — BULLISH MILD VIX NORMAL");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S03 → expected BULLISH MILD MEDIUM", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.BULLISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.MILD);
        assertThat(entity.getCompositeScore())
                .isGreaterThan(new BigDecimal("0.25"))
                .isLessThan(new BigDecimal("0.50"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.NORMAL);
        assertThat(entity.getCommentaryDivergence()).isFalse();
    }

    /**
     * S05 — NEUTRAL WEAK | VIX HIGH | LOW confidence.
     *
     * Tiers pull in opposite directions and cancel to near zero.
     * Only T1B (avg=0) and T4 (avg=0) agree with NEUTRAL → base=2/5=0.40.
     * VIX HIGH ×0.85 → 0.40×0.85 = 0.34 → LOW.
     *
     * T1A  [spot>ema20(+1) spot>ema50(+1) spot<ema200(−1) higherHighs+null(0) premium=25(+1) pcr=0.95(0) maxPain(0)]  = 2/7 = 0.286 → +0.086
     * T1B  [ema20<ema50(−1) ema50<ema200(−1) rsi=55(0) macd=10>5(+1) candle(+1)]                                     = 0/5 = 0.000 →  0.000
     * T2   [fiiNet=−400(0) fiiRatio=0.35(−1) fiiOpt=300(0) dii=200(0)]                                               = −1/4 = −0.250 → −0.075
     * T3   [vixChange=+2.4%(0) callOI>putOI(−1) giftNifty=20(0)]                                                     = −1/3 = −0.333 → −0.033
     * T4   [marketaux=0.10(0) commentary=null(0)]                                                                     = 0/2 = 0.000 →  0.000
     * composite ≈ −0.022 → NEUTRAL WEAK
     * confidence = 2/5 × 0.85 × 1.00 = 0.34 → LOW
     */
    @Test
    void s05_neutralWeak_vixHigh_tiersCancel_producesLowConfidence() {
        // ema20 < ema50 → T1B bearish crossover; ema200 above spot → T1A split
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("23800"),   // ema20  — spot(24000)>ema20 → +1 in T1A; ema20<ema50 → −1 in T1B
                new BigDecimal("23850"),   // ema50  — spot(24000)>ema50 → +1 in T1A; ema50<ema200 → −1 in T1B
                new BigDecimal("24200"),   // ema200 — spot(24000)<ema200 → −1 in T1A; ema50<ema200 → −1 in T1B
                new BigDecimal("55"),      // rsi 40–60 → 0
                new BigDecimal("10"),      // macdLine > macdSignal → +1
                new BigDecimal("5"),
                null,
                true,                      // bullish candle → +1 in T1B (partially offsets T1B bears)
                false,
                true,                      // higherHighs=true, higherLows=null → vote=0 (null check)
                null
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24000"))
                .futuresPremium(new BigDecimal("25"))    // >20 → +1 in T1A
                .pcr(new BigDecimal("0.95"))              // neutral → 0
                .vixLevel(new BigDecimal("21.00"))
                .vixPrevLevel(new BigDecimal("20.50"))   // pct change = +2.4%, within −2% to +10% → 0
                .vixRegime(VixRegime.HIGH)
                .fiiNetFutures(new BigDecimal("-400"))   // within ±500 → 0
                .fiiLongRatio(new BigDecimal("0.35"))    // <0.40 → −1 bearish
                .fiiNetOptions(new BigDecimal("300"))    // <500 → 0
                .diiNet(new BigDecimal("200"))           // <500 → 0
                .callOiChange(new BigDecimal("300"))
                .putOiChange(new BigDecimal("100"))      // call>put → −1 in T3
                .giftNiftyPremium(new BigDecimal("20"))  // <50 → 0
                .marketauxSentiment(new BigDecimal("0.10"))   // neutral → 0
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S05 — NEUTRAL WEAK VIX HIGH tiers cancel");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S05 → expected NEUTRAL WEAK LOW", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.NEUTRAL);
        assertThat(entity.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(entity.getCompositeScore())
                .isGreaterThanOrEqualTo(new BigDecimal("-0.1000"))
                .isLessThanOrEqualTo(new BigDecimal("0.1000"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.HIGH);
    }

    /**
     * S06 — NEUTRAL WEAK | VIX NORMAL | MEDIUM confidence.
     *
     * T1B, T2, T4 all have avg=0 (agree with NEUTRAL) → base=3/5=0.60.
     * VIX NORMAL ×1.00 → 0.60 → MEDIUM.
     * Agent 2 would select ShortStrangle (NEUTRAL + IV Rich per seed).
     *
     * T1A  [spot<ema20(−1) spot>ema50(+1) spot<ema200(−1) higherHighs=null(0) premium=10(0) pcr=0.95(0) maxPain(0)]  = −1/7 = −0.143 (|avg|>0.10 → not NEUTRAL)
     * T1B  [ema20>ema50(+1) ema50<ema200(−1) rsi=50(0) macd=5=5(0) candle(0)]                                        = 0/5 = 0.000 (agrees NEUTRAL)
     * T2   [fiiNet=100(0) fiiRatio=0.50(0) fiiOpt=50(0) dii=200(0)]                                                  = 0/4 = 0.000 (agrees NEUTRAL)
     * T3   [vixChange=+1.3%(0) callOI>putOI(−1) giftNifty=20(0)]                                                     = −1/3 = −0.333 (|avg|>0.10 → not NEUTRAL)
     * T4   [marketaux=0.05(0) commentary=null(0)]                                                                     = 0/2 = 0.000 (agrees NEUTRAL)
     * composite ≈ −0.076 → NEUTRAL WEAK
     * confidence = 3/5 × 1.00 × 1.00 = 0.60 → MEDIUM
     */
    @Test
    void s06_neutralWeak_vixNormal_threeTiersFlat_producesMediumConfidence() {
        // ema20=24100 > spot=24050 → spot<ema20; ema50=23900 < spot → spot>ema50; ema200=24200 > spot → spot<ema200
        // T1B: ema20(24100)>ema50(23900) → +1; ema50(23900)<ema200(24200) → −1; net=0
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("24100"),   // ema20 > spot=24050 → T1A: −1; T1B: ema20>ema50 → +1
                new BigDecimal("23900"),   // ema50 < spot=24050 → T1A: +1; T1B: ema50<ema200 → −1
                new BigDecimal("24200"),   // ema200 > spot=24050 → T1A: −1
                new BigDecimal("50"),      // rsi 40–60 → 0
                new BigDecimal("5"),       // macdLine == macdSignal → 0
                new BigDecimal("5"),
                null,
                false,
                false,
                null,                      // higherHighs null → vote=0
                null
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24050"))
                .futuresPremium(new BigDecimal("10"))    // <20 → 0
                .pcr(new BigDecimal("0.95"))              // neutral → 0
                .vixLevel(new BigDecimal("15.50"))
                .vixPrevLevel(new BigDecimal("15.30"))   // pct change = +1.3%, within −2% to +10% → 0
                .vixRegime(VixRegime.NORMAL)
                .fiiNetFutures(new BigDecimal("100"))    // <500 → 0
                .fiiLongRatio(new BigDecimal("0.50"))    // neutral → 0
                .fiiNetOptions(new BigDecimal("50"))     // <500 → 0
                .diiNet(new BigDecimal("200"))           // <500 → 0
                .callOiChange(new BigDecimal("300"))
                .putOiChange(new BigDecimal("100"))      // call>put → −1 in T3
                .giftNiftyPremium(new BigDecimal("20"))  // <50 → 0
                .marketauxSentiment(new BigDecimal("0.05"))   // neutral → 0
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S06 — NEUTRAL WEAK VIX NORMAL 3 flat tiers");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S06 → expected NEUTRAL WEAK MEDIUM", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.NEUTRAL);
        assertThat(entity.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(entity.getCompositeScore())
                .isGreaterThanOrEqualTo(new BigDecimal("-0.1000"))
                .isLessThanOrEqualTo(new BigDecimal("0.1000"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.NORMAL);
    }

    /**
     * S07 — NEUTRAL WEAK | VIX LOW | MEDIUM confidence.
     *
     * VIX LOW applies ×1.10 boost. T1B, T2, T4 all at avg=0 → base=3/5=0.60.
     * 0.60×1.10 = 0.66 → MEDIUM.
     * Agent 2 SKIPS when VIX Low + IV Cheap (strategy matrix SKIP path).
     *
     * T1A  [spot>ema20(+1) spot>ema50(+1) spot<ema200(−1) higherHighs=null(0) premium=10(0) pcr=0.95(0) maxPain(0)]  = 1/7 = 0.143 (|avg|>0.10)
     * T1B  [ema20>ema50(+1) ema50<ema200(−1) rsi=50(0) macd=5=5(0) candle(0)]                                        = 0/5 = 0.000 (agrees NEUTRAL)
     * T2   [fiiNet=100(0) fiiRatio=0.50(0) fiiOpt=50(0) dii=200(0)]                                                  = 0/4 = 0.000 (agrees NEUTRAL)
     * T3   [vixChange=−4.3%(+1) oi(0) giftNifty=20(0)]                                                               = 1/3 = 0.333 (|avg|>0.10)
     * T4   [marketaux=0.05(0) commentary=null(0)]                                                                     = 0/2 = 0.000 (agrees NEUTRAL)
     * composite ≈ +0.076 → NEUTRAL WEAK (just below 0.10 boundary)
     * confidence = 3/5 × 1.10 × 1.00 = 0.66 → MEDIUM
     */
    @Test
    void s07_neutralWeak_vixLow_boostModifier_producesMediumConfidence() {
        // spot=24050, ema20=23900(+1 in T1A), ema50=23800(+1 in T1A), ema200=24300(−1 in T1A)
        // T1B: ema20(23900)>ema50(23800) → +1; ema50(23800)<ema200(24300) → −1; net=0
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("23900"),   // ema20 < spot=24050 → T1A: +1; T1B: ema20>ema50 → +1
                new BigDecimal("23800"),   // ema50 < spot=24050 → T1A: +1; T1B: ema50<ema200 → −1
                new BigDecimal("24300"),   // ema200 > spot=24050 → T1A: −1
                new BigDecimal("50"),      // rsi neutral → 0
                new BigDecimal("5"),       // macdLine == macdSignal → 0
                new BigDecimal("5"),
                null,
                false,
                false,
                null,                      // higherHighs null → 0
                null
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24050"))
                .futuresPremium(new BigDecimal("10"))    // <20 → 0
                .pcr(new BigDecimal("0.95"))              // neutral → 0
                .vixLevel(new BigDecimal("11.00"))
                .vixPrevLevel(new BigDecimal("11.50"))   // pct change = −4.3% < −2% → +1 in T3
                .vixRegime(VixRegime.LOW)
                .fiiNetFutures(new BigDecimal("100"))    // <500 → 0
                .fiiLongRatio(new BigDecimal("0.50"))    // neutral → 0
                .fiiNetOptions(new BigDecimal("50"))     // <500 → 0
                .diiNet(new BigDecimal("200"))           // <500 → 0
                .giftNiftyPremium(new BigDecimal("20"))  // <50 → 0
                .marketauxSentiment(new BigDecimal("0.05"))   // neutral → 0
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S07 — NEUTRAL WEAK VIX LOW ×1.10 boost");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S07 → expected NEUTRAL WEAK MEDIUM (VIX LOW ×1.10)", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.NEUTRAL);
        assertThat(entity.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(entity.getCompositeScore())
                .isGreaterThanOrEqualTo(new BigDecimal("-0.1000"))
                .isLessThanOrEqualTo(new BigDecimal("0.1000"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.LOW);
    }

    /**
     * S08 — NEUTRAL WEAK | VIX EXTREME | LOW confidence.
     *
     * VIX EXTREME applies crushing ×0.60. Even with 2 tiers neutral (T2=0, T4=0),
     * base=2/5=0.40 → 0.40×0.60 = 0.24 → LOW.
     * Agent 2 SKIPS and flags to user when VIX EXTREME (>24).
     *
     * T1A  [spot<ema20(−1) spot>ema50(+1) spot>ema200(+1) higherHighs=null(0) premium=10(0) pcr=0.95(0) maxPain(0)]  = 1/7 = 0.143 (|avg|>0.10)
     * T1B  [ema20>ema50(+1) ema50>ema200(+1) rsi=50(0) macd=−2<2(−1) candle(0)]                                      = 1/5 = 0.200 (|avg|>0.10)
     * T2   [all null → 0,0,0,0]                                                                                        = 0/4 = 0.000 (agrees NEUTRAL)
     * T3   [vixChange=+12.2%(−1) callOI>putOI(−1) giftNifty=−30(0)]                                                  = −2/3 = −0.667 (|avg|>0.10)
     * T4   [marketaux=null(0) commentary=null(0)]                                                                      = 0/2 = 0.000 (agrees NEUTRAL)
     * composite ≈ +0.016 → NEUTRAL WEAK
     * confidence = 2/5 × 0.60 × 1.00 = 0.24 → LOW
     */
    @Test
    void s08_neutralWeak_vixExtreme_crushesConfidence_producesLowConfidence() {
        // ema20=23200 > spot=23000 → T1A: −1; T1B: ema20>ema50 → +1
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("23200"),   // ema20 > spot=23000 → T1A: −1; T1B: ema20>ema50(22900) → +1
                new BigDecimal("22900"),   // ema50 < spot=23000 → T1A: +1; T1B: ema50>ema200(22500) → +1
                new BigDecimal("22500"),   // ema200 < spot=23000 → T1A: +1
                new BigDecimal("50"),      // rsi neutral → 0
                new BigDecimal("-2"),      // macdLine < macdSignal → −1
                new BigDecimal("2"),
                null,
                false,
                false,
                null,                      // higherHighs null → 0
                null
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("23000"))
                .futuresPremium(new BigDecimal("10"))    // <20 → 0
                .pcr(new BigDecimal("0.95"))              // neutral → 0
                .vixLevel(new BigDecimal("27.50"))
                .vixPrevLevel(new BigDecimal("24.50"))   // pct change = +12.2% > +10% → −1 in T3
                .vixRegime(VixRegime.EXTREME)
                .callOiChange(new BigDecimal("300"))
                .putOiChange(new BigDecimal("100"))      // call>put → −1 in T3
                .giftNiftyPremium(new BigDecimal("-30")) // −30 within ±50 → 0
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S08 — NEUTRAL WEAK VIX EXTREME ×0.60");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S08 → expected NEUTRAL WEAK LOW (VIX EXTREME ×0.60 crushes confidence)", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.NEUTRAL);
        assertThat(entity.getStrength()).isEqualTo(Strength.WEAK);
        assertThat(entity.getCompositeScore())
                .isGreaterThanOrEqualTo(new BigDecimal("-0.1000"))
                .isLessThanOrEqualTo(new BigDecimal("0.1000"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.EXTREME);
    }

    /**
     * S09 — BEARISH MILD | VIX HIGH | MEDIUM confidence.
     *
     * T1A, T1B, T2 all bearish → 3/5 agree with BEARISH.
     * VIX HIGH ×0.85 → 0.60×0.85 = 0.51 → MEDIUM.
     *
     * T1A  [spot<ema20(−1) spot<ema50(−1) spot>ema200(+1) HH+LL(−1) premium=−25(−1) pcr=0.72(−1) maxPain(0)]  = −4/7 = −0.571 → −0.171
     * T1B  [ema20>ema50(+1) ema50>ema200(+1) rsi=38(−1) macd=−20<−5(−1) bearishCandle(−1)]                    = −1/5 = −0.200 → −0.040
     * T2   [fiiNet=−600(−1) fiiRatio=0.38(−1) fiiOpt=−600(−1) dii=300(0)]                                     = −3/4 = −0.750 → −0.225
     * T3   [vixChange=+1.5%(0) oi(0) giftNifty=−20(0)]                                                         = 0/3 = 0.000 →  0.000
     * T4   [marketaux=−0.20(0) commentary=null(0)]                                                              = 0/2 = 0.000 →  0.000
     * composite ≈ −0.436 → BEARISH MILD
     * confidence = 3/5 × 0.85 × 1.00 = 0.51 → MEDIUM
     */
    @Test
    void s09_bearishMild_vixHigh_threeTiersBearish_producesMediumConfidence() {
        // ema20=23800 > ema50=23700 → T1B ema20>ema50 → +1 (crossover not yet bearish)
        // spot=23600 < ema20=23800 and < ema50=23700 → T1A bearish
        // spot=23600 > ema200=23500 → T1A: +1 for ema200 (early bear, long-term still above 200EMA)
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("23800"),   // ema20 > spot=23600 → T1A: −1; T1B: ema20>ema50 → +1
                new BigDecimal("23700"),   // ema50 > spot=23600 → T1A: −1; T1B: ema50>ema200 → +1
                new BigDecimal("23500"),   // ema200 < spot=23600 → T1A: +1
                new BigDecimal("38"),      // rsi <40 → −1
                new BigDecimal("-20"),     // macdLine < macdSignal → −1
                new BigDecimal("-5"),
                null,
                false,
                true,                      // bearish candle → −1
                false,                     // lowerHighs
                false                      // lowerLows → −1 for HH+HL vote
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("23600"))
                .futuresPremium(new BigDecimal("-25"))   // discount >20 → −1
                .pcr(new BigDecimal("0.72"))              // <0.80 → −1
                .vixLevel(new BigDecimal("20.80"))
                .vixPrevLevel(new BigDecimal("20.50"))   // pct change = +1.5%, within range → 0
                .vixRegime(VixRegime.HIGH)
                .fiiNetFutures(new BigDecimal("-600"))   // <−500 → −1
                .fiiLongRatio(new BigDecimal("0.38"))    // <0.40 → −1
                .fiiNetOptions(new BigDecimal("-600"))   // <−500 → −1
                .diiNet(new BigDecimal("300"))           // <500 → 0
                .giftNiftyPremium(new BigDecimal("-20")) // within ±50 → 0
                .marketauxSentiment(new BigDecimal("-0.20"))  // within −0.30 to 0.30 → 0
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S09 — BEARISH MILD VIX HIGH");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S09 → expected BEARISH MILD MEDIUM", tierScores, entity);

        assertThat(entity.getBias()).isEqualTo(Bias.BEARISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.MILD);
        assertThat(entity.getCompositeScore())
                .isGreaterThan(new BigDecimal("-0.50"))
                .isLessThan(new BigDecimal("-0.25"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.HIGH);
        assertThat(entity.getCommentaryDivergence()).isFalse();
    }

    /**
     * S11 — BULLISH MILD | T2 all null (data gaps) | LOW confidence.
     *
     * Simulates the "FII/DII data unavailable" scenario.
     * All fiiNetFutures / fiiLongRatio / fiiNetOptions / diiNet passed as null.
     * T2 scorer receives all nulls → every signal scores 0 → T2 average=0, contribution=0.
     * Only T1A and T1B contribute positively → composite still in BULLISH MILD range.
     * But only 2 tiers agree with BULLISH → base=2/5=0.40 → LOW.
     *
     * T1A  [spot>ema20(+1) spot>ema50(+1) spot>ema200(+1) HH+HL(+1) premium=25(+1) pcr=1.30(+1) maxPain(0)]  = 6/7 = 0.857 → +0.257
     * T1B  [ema20>ema50(+1) ema50>ema200(+1) rsi=62(+1) macd(+1) candle(+1)]                                  = 5/5 = 1.000 → +0.200
     * T2   [all null → 0,0,0,0]                                                                                 = 0/4 = 0.000 →  0.000  ← data gap
     * T3   [vixChange=−1.2%(0) oi(0) giftNifty=30(0)]                                                          = 0/3 = 0.000 →  0.000
     * T4   [marketaux=0.10(0) commentary=null(0)]                                                               = 0/2 = 0.000 →  0.000
     * composite ≈ 0.457 → BULLISH MILD
     * confidence = 2/5 × 1.00 × 1.00 = 0.40 → LOW (just below 0.41 threshold)
     */
    @Test
    void s11_bullishMild_tier2AllNull_missingData_producesLowConfidence() {
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("23800"),   // ema20 < spot=24100 → +1 in both T1A and T1B crossover
                new BigDecimal("23600"),   // ema50 < spot=24100 → +1 T1A; ema50>ema200 → +1 T1B
                new BigDecimal("23100"),   // ema200 < spot=24100 → +1 T1A
                new BigDecimal("62"),      // rsi >60 → +1
                new BigDecimal("30"),      // macdLine > macdSignal → +1
                new BigDecimal("20"),
                null,
                true,                      // bullish candle → +1
                false,
                true,                      // higherHighs → +1
                true                       // higherLows  → +1
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24100"))
                .futuresPremium(new BigDecimal("25"))    // >20 → +1
                .pcr(new BigDecimal("1.30"))              // >1.20 → +1
                .vixLevel(new BigDecimal("17.00"))
                .vixPrevLevel(new BigDecimal("17.20"))   // pct change = −1.2%, within range → 0
                .vixRegime(VixRegime.NORMAL)
                // T2 — ALL NULL: simulates pre-market run before FII/DII data is published
                .fiiNetFutures(null)
                .fiiLongRatio(null)
                .fiiNetOptions(null)
                .diiNet(null)
                .giftNiftyPremium(new BigDecimal("30"))  // <50 → 0
                .marketauxSentiment(new BigDecimal("0.10"))   // neutral → 0
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S11 — BULLISH MILD T2 all null (data gaps)");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S11 → expected BULLISH MILD LOW (T2=0 data gap)", tierScores, entity);

        // Verify T2 correctly returned zero when all inputs null
        TierScore tier2 = findTier(tierScores, "TIER_2");
        assertThat(tier2.average())
                .as("T2 avg must be 0 when all FII/DII inputs are null")
                .isEqualByComparingTo("0.0000");
        assertThat(tier2.signals().values())
                .as("All T2 signal votes must be 0 (null inputs → score 0 per CLAUDE.md §2)")
                .allMatch(v -> v == 0);

        assertThat(entity.getBias()).isEqualTo(Bias.BULLISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.MILD);
        assertThat(entity.getCompositeScore())
                .isGreaterThan(new BigDecimal("0.25"))
                .isLessThan(new BigDecimal("0.50"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.NORMAL);
    }

    /**
     * S12 — BULLISH MILD | Commentary divergence ×0.80 penalty | LOW confidence.
     *
     * T1A and T1B are bullish → overall signal BULLISH MILD.
     * T4: commentary="BEARISH" and marketaux=−0.35 → T4 average=−1.0 (bearish).
     * SignalComposer.isTier4Diverging(): T4 avg < 0 while overall=BULLISH → divergence=true.
     * Penalty: confidence ×0.80.
     * base=2/5=0.40 (T1A+T1B only agree BULLISH; T2/T3 neutral; T4 opposes)
     * After penalty: 0.40×0.80 = 0.32 → LOW.
     *
     * T1A  [spot>ema20(+1) spot>ema50(+1) spot>ema200(+1) HH+HL(+1) premium=25(+1) pcr=1.30(+1) maxPain(0)]  = 6/7 = 0.857 → +0.257
     * T1B  [ema20>ema50(+1) ema50>ema200(+1) rsi=54(0) macd(+1) candle(0)]                                    = 3/5 = 0.600 → +0.120
     * T2   [fiiNet=200(0) fiiRatio=0.48(0) fiiOpt=100(0) dii=300(0)]                                          = 0/4 = 0.000 →  0.000
     * T3   [vixChange=−1.8%(0) oi(0) giftNifty=25(0)]                                                         = 0/3 = 0.000 →  0.000
     * T4   [marketaux=−0.35(−1) commentary=BEARISH(−1)]   ← DIVERGING from BULLISH overall                    = −2/2 = −1.000 → −0.100
     * composite ≈ 0.277 → BULLISH MILD
     * base confidence = 2/5 = 0.40  (T1A + T1B agree; T4 opposes so doesn't count)
     * pre-penalty: 0.40 × 1.00 × 1.00 = 0.40
     * divergence penalty ×0.80 → 0.40 × 0.80 = 0.32 → LOW
     */
    @Test
    void s12_bullishMild_commentaryDiverges_penaltyApplied_producesLowConfidence() {
        PrecomputedIndicators ind = new PrecomputedIndicators(
                new BigDecimal("23900"),   // ema20 < spot=24050 → T1A: +1; T1B: ema20>ema50 → +1
                new BigDecimal("23700"),   // ema50 < spot=24050 → T1A: +1; T1B: ema50>ema200 → +1
                new BigDecimal("23200"),   // ema200 < spot=24050 → T1A: +1
                new BigDecimal("54"),      // rsi 40–60 → 0
                new BigDecimal("20"),      // macdLine > macdSignal → +1
                new BigDecimal("15"),
                null,
                false,
                false,
                true,                      // higherHighs → +1
                true                       // higherLows  → +1
        );

        MarketInputs inputs = MarketInputs.builder()
                .spot(new BigDecimal("24050"))
                .futuresPremium(new BigDecimal("25"))    // >20 → +1
                .pcr(new BigDecimal("1.30"))              // >1.20 → +1
                .vixLevel(new BigDecimal("16.50"))
                .vixPrevLevel(new BigDecimal("16.80"))   // pct change = −1.8%, within range → 0
                .vixRegime(VixRegime.NORMAL)
                .fiiNetFutures(new BigDecimal("200"))    // <500 → 0
                .fiiLongRatio(new BigDecimal("0.48"))    // neutral → 0
                .fiiNetOptions(new BigDecimal("100"))    // <500 → 0
                .diiNet(new BigDecimal("300"))           // <500 → 0
                .giftNiftyPremium(new BigDecimal("25"))  // <50 → 0
                // T4 DIVERGES from bullish T1-T3: LLM extracted BEARISH, Marketaux also negative
                .marketauxSentiment(new BigDecimal("-0.35"))  // <−0.30 → −1
                .commentaryBias("BEARISH")               // → −1
                .indicators(ind)
                .expiryDate(EXPIRY)
                .build();

        List<TierScore> tierScores = runScorers(inputs, "S12 — BULLISH MILD with commentary divergence");
        Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
        logResult("S12 → expected BULLISH MILD LOW (divergence ×0.80 penalty)", tierScores, entity);

        // T4 must be bearish (for divergence to fire)
        TierScore tier4 = findTier(tierScores, "TIER_4");
        assertThat(tier4.average())
                .as("T4 must be bearish (both signals −1)")
                .isLessThan(BigDecimal.ZERO);

        assertThat(entity.getCommentaryDivergence())
                .as("Divergence flag must be set when T4 opposes overall BULLISH direction")
                .isTrue();
        assertThat(entity.getBias()).isEqualTo(Bias.BULLISH);
        assertThat(entity.getStrength()).isEqualTo(Strength.MILD);
        assertThat(entity.getCompositeScore())
                .isGreaterThan(new BigDecimal("0.25"))
                .isLessThan(new BigDecimal("0.50"));
        assertThat(entity.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(entity.getVixRegime()).isEqualTo(VixRegime.NORMAL);
    }

    /**
     * User-configurable scenario — edit src/test/resources/configured-scenario.json,
     * then run: mvn test -pl agent1-market_analyst -Dtest="BacktestScenarioTest#configuredTest"
     *
     * No hard assertions on bias/strength/confidence — you control the inputs.
     * Only asserts the pipeline does not throw and returns a valid entity.
     *
     * vixRegime:      LOW | NORMAL | HIGH | EXTREME
     * commentaryBias: BULLISH | BEARISH | NEUTRAL | null
     * null numeric fields → score 0 (data unavailable), never throws.
     */
    @Test
    void configuredTest() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("configured-scenario.json")) {
            assertThat(is)
                    .as("configured-scenario.json not found — create it in "
                            + "agent1-market_analyst/src/test/resources/")
                    .isNotNull();

            ScenarioConfig cfg = mapper.readValue(is, ScenarioConfig.class);
            ScenarioConfig.Indicators ci = cfg.indicators != null
                    ? cfg.indicators : new ScenarioConfig.Indicators();

            PrecomputedIndicators ind = new PrecomputedIndicators(
                    ci.ema20, ci.ema50, ci.ema200,
                    ci.rsi14, ci.macdLine, ci.macdSignal, ci.adx14,
                    Boolean.TRUE.equals(ci.bullishCandlePattern),
                    Boolean.TRUE.equals(ci.bearishCandlePattern),
                    ci.higherHighs, ci.higherLows
            );

            MarketInputs inputs = MarketInputs.builder()
                    .spot(cfg.spot)
                    .futuresPremium(cfg.futuresPremium)
                    .pcr(cfg.pcr)
                    .vixLevel(cfg.vixLevel)
                    .vixPrevLevel(cfg.vixPrevLevel)
                    .vixRegime(cfg.vixRegime != null ? VixRegime.valueOf(cfg.vixRegime) : null)
                    .fiiNetFutures(cfg.fiiNetFutures)
                    .fiiLongRatio(cfg.fiiLongRatio)
                    .fiiNetOptions(cfg.fiiNetOptions)
                    .diiNet(cfg.diiNet)
                    .callOiChange(cfg.callOiChange)
                    .putOiChange(cfg.putOiChange)
                    .giftNiftyPremium(cfg.giftNiftyPremium)
                    .marketauxSentiment(cfg.marketauxSentiment)
                    .commentaryBias(cfg.commentaryBias)
                    .indicators(ind)
                    .expiryDate(cfg.expiryDate != null ? LocalDate.parse(cfg.expiryDate) : EXPIRY)
                    .build();

            List<TierScore> tierScores = runScorers(inputs, "CONFIGURED: " + cfg.scenarioName);
            Agent1SignalEntity entity = composer.compose(tierScores, inputs, LocalDateTime.now());
            logResult("CONFIGURED RESULT", tierScores, entity);

            assertThat(entity.getBias()).isNotNull();
            assertThat(entity.getStrength()).isNotNull();
            assertThat(entity.getConfidence()).isNotNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<TierScore> runScorers(MarketInputs inputs, String scenario) {
        log.info("");
        log.info("══════════════════════════════════════════════════════════════");
        log.info("  SCENARIO: {}", scenario);
        log.info("══════════════════════════════════════════════════════════════");
        logInputs(inputs);
        return scorers.stream().map(s -> s.calculate(inputs)).toList();
    }

    private void logInputs(MarketInputs inputs) {
        log.info("  INPUTS:");
        log.info("    spot={}  vix={}({})  vixPrev={}",
                inputs.getSpot(), inputs.getVixLevel(), inputs.getVixRegime(), inputs.getVixPrevLevel());
        log.info("    pcr={}  futuresPremium={}",
                inputs.getPcr(), inputs.getFuturesPremium());
        var i = inputs.getIndicators();
        log.info("    ema20={}  ema50={}  ema200={}",
                i.ema20(), i.ema50(), i.ema200());
        log.info("    higherHighs={}  higherLows={}  rsi={}  macd={}/{}  adx={}",
                i.higherHighs(), i.higherLows(), i.rsi14(), i.macdLine(), i.macdSignal(), i.adx14());
        log.info("    fiiNet={}Cr  fiiRatio={}  fiiOpt={}Cr  dii={}Cr",
                inputs.getFiiNetFutures(), inputs.getFiiLongRatio(),
                inputs.getFiiNetOptions(), inputs.getDiiNet());
        log.info("    callOI={}  putOI={}  giftNifty={}pts",
                inputs.getCallOiChange(), inputs.getPutOiChange(), inputs.getGiftNiftyPremium());
        log.info("    marketaux={}  commentary={}",
                inputs.getMarketauxSentiment(), inputs.getCommentaryBias());
        log.info("  ── scorer detail follows ──");
    }

    private void logResult(String label, List<TierScore> tierScores, Agent1SignalEntity entity) {
        log.info("  ── TIER SCORES ──");
        tierScores.forEach(t ->
                log.info("    {} (w={}) : avg={} → contrib={}",
                        t.tierName(), t.weight(), t.average(), t.contribution()));
        log.info("  ── RESULT ({}) ──", label);
        log.info("    compositeScore={}  bias={}  strength={}",
                entity.getCompositeScore(), entity.getBias(), entity.getStrength());
        log.info("    confidenceScore={}  confidenceLabel={}  divergence={}",
                entity.getConfidenceScore(), entity.getConfidence(), entity.getCommentaryDivergence());
        log.info("    vixRegime={}  vixDirection={}",
                entity.getVixRegime(), entity.getVixDirection());
        log.info("══════════════════════════════════════════════════════════════");
        log.info("");
    }

    private TierScore findTier(List<TierScore> tierScores, String nameContains) {
        return tierScores.stream()
                .filter(t -> t.tierName().contains(nameContains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tier not found containing: " + nameContains));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON config classes for configuredTest
    // ─────────────────────────────────────────────────────────────────────────

    static class ScenarioConfig {
        public String     scenarioName;
        public BigDecimal spot;
        public BigDecimal futuresPremium;
        public BigDecimal pcr;
        public BigDecimal vixLevel;
        public BigDecimal vixPrevLevel;
        public String     vixRegime;         // LOW | NORMAL | HIGH | EXTREME
        public BigDecimal fiiNetFutures;
        public BigDecimal fiiLongRatio;
        public BigDecimal fiiNetOptions;
        public BigDecimal diiNet;
        public BigDecimal callOiChange;
        public BigDecimal putOiChange;
        public BigDecimal giftNiftyPremium;
        public BigDecimal marketauxSentiment;
        public String     commentaryBias;    // BULLISH | BEARISH | NEUTRAL | null
        public String     expiryDate;        // YYYY-MM-DD
        public Indicators indicators;

        static class Indicators {
            public BigDecimal ema20;
            public BigDecimal ema50;
            public BigDecimal ema200;
            public BigDecimal rsi14;
            public BigDecimal macdLine;
            public BigDecimal macdSignal;
            public BigDecimal adx14;
            public Boolean    bullishCandlePattern;  // null → false
            public Boolean    bearishCandlePattern;  // null → false
            public Boolean    higherHighs;           // null → vote 0
            public Boolean    higherLows;            // null → vote 0
        }
    }
}
