package com.the3Cgrp.zupptrade.agent2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData;
import com.the3Cgrp.zupptrade.agent2.client.model.StrikeData;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationEngine;
import com.the3Cgrp.zupptrade.agent2.engine.layer1.StrategySelector;
import com.the3Cgrp.zupptrade.agent2.engine.layer2.ExpectedMoveCalculator;
import com.the3Cgrp.zupptrade.agent2.engine.layer3.StrikeSelector;
import com.the3Cgrp.zupptrade.agent2.engine.layer4.GateValidator;
import com.the3Cgrp.zupptrade.agent2.engine.layer5.PositionSizer;
import com.the3Cgrp.zupptrade.agent2.engine.math.BlackScholesCalculator;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end scenario tests for the Agent 2 RecommendationEngine.
 *
 * No Spring context, no DB, no external APIs — pure unit tests against all 5 layers.
 * The engine under test: StrategySelector → ExpectedMoveCalculator → StrikeSelector
 *                        → GateValidator → PositionSizer
 *
 * Math worked examples are in the comments of each test.
 */
class RecommendationScenarioTest {

    private static final Logger log = LoggerFactory.getLogger(RecommendationScenarioTest.class);

    private static final LocalDate EXPIRY   = LocalDate.of(2026, 7, 7);
    private static final BigDecimal SPOT    = new BigDecimal("24000");
    private static final BigDecimal VIX_HIGH = new BigDecimal("20.0");
    private static final BigDecimal HV_14   = new BigDecimal("0.14");  // HV=14%

    private RecommendationEngine engine;

    @BeforeEach
    void setUp() {
        TradingConfig config = new TradingConfig();
        GateValidator gateValidator = new GateValidator(config);
        engine = new RecommendationEngine(
                new StrategySelector(config),
                new ExpectedMoveCalculator(new BlackScholesCalculator()),
                new StrikeSelector(config),
                gateValidator,
                new PositionSizer(gateValidator, config)
        );
    }

    // ── S1: Credit path — all gates pass ────────────────────────────────────────

    // Spot=24000, DTE=3, ATM IV=0.185, HV=0.14
    //   IV/HV = 1.32 > 1.05 → RICH
    //   EM = 24000 × 0.185 × √(3/365) ≈ 402; 1.2 SD boundary ≈ 483
    //   target short put = roundDown(24000 − 483) = 23500
    //   short 23500 ltp=22, long 23450 ltp=16, spread=50, netPremium=6
    //   maxLossPerLot = (50×65)−(6×65) = 2860
    //   realLossPerLot = 2860×0.50 = 1430; budget = 500000×1.5% = 7500
    //   lots = floor(7500/1430) = 5; maxProfit = 6×65×5 = 1950
    //   RoC = 0.39%; minRoc = 0.5%×3/7 = 0.21% → G4 passes
    @Test
    void s1_bullishMild_vixHigh_ivRich_bullPutSpread_allGatesPass() {
        Agent1SignalEntity signal = buildSignal(
                Bias.BULLISH, Strength.MILD, Confidence.MEDIUM, VixRegime.HIGH, VIX_HIGH);

        OptionChainData chain = buildChain(SPOT, 24000,
                new BigDecimal("200"), new BigDecimal("200"),
                List.of(
                        strike(24000, OptionType.CE, new BigDecimal("200"), new BigDecimal("0.185"),
                                new BigDecimal("0.50"), new BigDecimal("0.50"))
                ),
                List.of(
                        strike(23500, OptionType.PE, new BigDecimal("22"), new BigDecimal("0.195"),
                                new BigDecimal("-0.16"), new BigDecimal("0.16")),
                        strike(23450, OptionType.PE, new BigDecimal("16"), new BigDecimal("0.200"),
                                new BigDecimal("-0.13"), new BigDecimal("0.20"))
                ));

        RecommendationContext ctx = buildCtx(signal, buildProfile(new BigDecimal("500000")),
                HV_14, chain, SPOT, VIX_HIGH, 3);

        engine.execute(ctx);
        logResult("S1 BULLISH MILD VIX HIGH IV RICH → BULL_PUT_SPREAD", ctx);

        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BULL_PUT_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
        assertThat(ctx.getIvRegime()).isEqualTo(IvRegime.RICH);
        assertThat(ctx.getShortLeg().strike()).isEqualTo(23500);
        assertThat(ctx.getLongLeg().strike()).isEqualTo(23450);
        assertThat(ctx.isAllHardGatesPassed()).isTrue();
        assertThat(gate(ctx, "G1").passed()).isTrue();
        assertThat(gate(ctx, "G3").passed()).isTrue();
        assertThat(gate(ctx, "G4").passed()).isTrue();
        assertThat(ctx.getLots()).isEqualTo(5);
        assertThat(ctx.getMaxProfitTotal()).isEqualByComparingTo(new BigDecimal("1950")); // 6×65×5
    }

    // ── S2: Debit path — BULLISH EXTREME → BULL_CALL_SPREAD regardless of IV ──

    // Spot=24020, DTE=7, ATM call ltp=40, short call (ATM+200) ltp=10
    //   netDebit=30, maxProfit=170, R:R=5.67 ≥ 3 → G1D passes
    //   breakeven = 24000+30 = 24030; distance from spot = 10 ≤ 30 → G2D passes
    //   maxLossPerLot = 30×65 = 1950; budget = 500000×0.5% = 2500
    //   lots = floor(2500/1950) = 1; G4D: 1950 ≤ 2500 → G4D passes
    @Test
    void s2_bullishExtreme_vixNormal_bullCallSpread_debit_allGatesPass() {
        BigDecimal spot = new BigDecimal("24020");
        Agent1SignalEntity signal = buildSignal(
                Bias.BULLISH, Strength.EXTREME, Confidence.HIGH, VixRegime.NORMAL,
                new BigDecimal("16.0"));

        OptionChainData chain = buildChain(spot, 24000,
                new BigDecimal("40"), new BigDecimal("40"),
                List.of(
                        strike(24000, OptionType.CE, new BigDecimal("40"), new BigDecimal("0.15"),
                                new BigDecimal("0.50"), new BigDecimal("0.50")),
                        strike(24200, OptionType.CE, new BigDecimal("10"), new BigDecimal("0.12"),
                                new BigDecimal("0.18"), new BigDecimal("0.18"))
                ),
                List.of(
                        strike(24000, OptionType.PE, new BigDecimal("35"), new BigDecimal("0.15"),
                                new BigDecimal("-0.50"), new BigDecimal("0.50"))
                ));

        RecommendationContext ctx = buildCtx(signal, buildProfile(new BigDecimal("500000")),
                new BigDecimal("0.12"), chain, spot, new BigDecimal("16.0"), 7);

        engine.execute(ctx);
        logResult("S2 BULLISH EXTREME VIX NORMAL → BULL_CALL_SPREAD (debit)", ctx);

        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BULL_CALL_SPREAD);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.DEBIT);
        assertThat(ctx.getLongLeg().strike()).isEqualTo(24000);   // long ATM
        assertThat(ctx.getShortLeg().strike()).isEqualTo(24200);  // short OTM
        assertThat(ctx.isAllHardGatesPassed()).isTrue();
        assertThat(gate(ctx, "G1D").passed()).isTrue();
        assertThat(gate(ctx, "G2D").passed()).isTrue();
        assertThat(gate(ctx, "G3D").passed()).isTrue();
        assertThat(gate(ctx, "G4D").passed()).isTrue();
        assertThat(ctx.getLots()).isGreaterThanOrEqualTo(1);
    }

    // ── S3: Iron Condor — all 4 legs populated ──────────────────────────────────

    // NEUTRAL WEAK + VIX HIGH + IV RICH → WIDE_IRON_CONDOR (credit)
    // Same EM as S1 (DTE=3); 1.2 SD boundary ≈ 483
    //   PE spread: short 23500, long 23450 (boundary = 23517 → target 23500)
    //   CE spread: short 24500, long 24550 (boundary = 24483 → round up to 24500)
    //   G1 uses PE short (pop=0.16 → sellerPoP=84% ≥ 80%)
    //   G3: |0.16−0.20|×100 = 4% ≤ 15%
    @Test
    void s3_neutralWeak_vixHigh_ivRich_wideIronCondor_fourLegsSet() {
        Agent1SignalEntity signal = buildSignal(
                Bias.NEUTRAL, Strength.WEAK, Confidence.MEDIUM, VixRegime.HIGH, VIX_HIGH);

        OptionChainData chain = buildChain(SPOT, 24000,
                new BigDecimal("200"), new BigDecimal("200"),
                List.of(
                        strike(24000, OptionType.CE, new BigDecimal("200"), new BigDecimal("0.185"),
                                new BigDecimal("0.50"), new BigDecimal("0.50")),
                        strike(24500, OptionType.CE, new BigDecimal("20"), new BigDecimal("0.170"),
                                new BigDecimal("0.14"), new BigDecimal("0.14")),
                        strike(24550, OptionType.CE, new BigDecimal("15"), new BigDecimal("0.165"),
                                new BigDecimal("0.10"), new BigDecimal("0.18"))
                ),
                List.of(
                        strike(23500, OptionType.PE, new BigDecimal("20"), new BigDecimal("0.195"),
                                new BigDecimal("-0.16"), new BigDecimal("0.16")),
                        strike(23450, OptionType.PE, new BigDecimal("15"), new BigDecimal("0.200"),
                                new BigDecimal("-0.13"), new BigDecimal("0.20"))
                ));

        RecommendationContext ctx = buildCtx(signal, buildProfile(new BigDecimal("500000")),
                HV_14, chain, SPOT, VIX_HIGH, 3);

        engine.execute(ctx);
        logResult("S3 NEUTRAL WEAK VIX HIGH IV RICH → WIDE_IRON_CONDOR", ctx);

        assertThat(ctx.getStrategy()).isEqualTo(Strategy.WIDE_IRON_CONDOR);
        assertThat(ctx.getSpreadDirection()).isEqualTo(SpreadDirection.CREDIT);
        assertThat(ctx.getShortLeg()).as("PE short leg").isNotNull();
        assertThat(ctx.getLongLeg()).as("PE long leg").isNotNull();
        assertThat(ctx.getShortLeg2()).as("CE short leg").isNotNull();
        assertThat(ctx.getLongLeg2()).as("CE long leg").isNotNull();
        assertThat(ctx.getShortLeg().optionType()).isEqualTo(OptionType.PE);
        assertThat(ctx.getLongLeg().optionType()).isEqualTo(OptionType.PE);
        assertThat(ctx.getShortLeg2().optionType()).isEqualTo(OptionType.CE);
        assertThat(ctx.getLongLeg2().optionType()).isEqualTo(OptionType.CE);
        assertThat(ctx.getShortLeg().strike()).isEqualTo(23500);
        assertThat(ctx.getShortLeg2().strike()).isEqualTo(24500);
        assertThat(ctx.isAllHardGatesPassed()).isTrue();
        assertThat(ctx.getLots()).isGreaterThanOrEqualTo(1);
    }

    // ── S4: VIX EXTREME → NO_TRADE, engine exits after Layer 1 ─────────────────

    @Test
    void s4_vixExtreme_noTrade_earlyExitAfterLayer1() {
        Agent1SignalEntity signal = buildSignal(
                Bias.BULLISH, Strength.MILD, Confidence.MEDIUM, VixRegime.EXTREME,
                new BigDecimal("28.0"));

        // No option chain needed — engine exits before Layer 2
        RecommendationContext ctx = new RecommendationContext();
        ctx.setSignal(signal);
        ctx.setSpot(SPOT);
        ctx.setVix(new BigDecimal("28.0"));
        ctx.setDte(3);
        ctx.setExpiryDate(EXPIRY);
        ctx.setLotSize(65);
        ctx.setUserProfile(buildProfile(new BigDecimal("500000")));

        engine.execute(ctx);
        logResult("S4 VIX EXTREME → NO_TRADE", ctx);

        assertThat(ctx.getStrategy()).isEqualTo(Strategy.NO_TRADE);
        assertThat(ctx.getShortLeg()).isNull();
        assertThat(ctx.getLongLeg()).isNull();
        assertThat(ctx.getGateResults()).isEmpty();
        assertThat(ctx.getLots()).isZero();
    }

    // ── S5: LOW confidence + credit path → NO_TRADE from Layer 1 ───────────────

    // VIX/100=0.20 as IV proxy (no chain), HV=0.14, ratio=1.43 → RICH
    // Candidate = BULL_PUT_SPREAD (credit), but LOW confidence blocks credit → NO_TRADE
    @Test
    void s5_lowConfidence_creditBlocked_noTrade() {
        Agent1SignalEntity signal = buildSignal(
                Bias.BULLISH, Strength.MILD, Confidence.LOW, VixRegime.HIGH, VIX_HIGH);

        // No chain — StrategySelector falls back to VIX/100 as IV proxy
        RecommendationContext ctx = new RecommendationContext();
        ctx.setSignal(signal);
        ctx.setSpot(SPOT);
        ctx.setVix(VIX_HIGH);
        ctx.setHistoricalVolatility(HV_14);  // ratio=0.20/0.14=1.43 → RICH; credit blocked by LOW
        ctx.setDte(3);
        ctx.setExpiryDate(EXPIRY);
        ctx.setLotSize(65);
        ctx.setUserProfile(buildProfile(new BigDecimal("500000")));

        engine.execute(ctx);
        logResult("S5 LOW CONFIDENCE CREDIT BLOCKED → NO_TRADE", ctx);

        assertThat(ctx.getStrategy()).isEqualTo(Strategy.NO_TRADE);
        assertThat(ctx.getShortLeg()).isNull();
        assertThat(ctx.getGateResults()).isEmpty();
    }

    // ── S6: G1 hard failure — seller PoP below 80% ──────────────────────────────

    // shortPut.pop=0.25 (buyer's ITM prob) → seller PoP = (1−0.25)×100 = 75% < 80% → G1 FAILS
    // G3: |0.25−0.32|×100 = 7% ≤ 15% → G3 passes (only G1 fails)
    // Engine exits before PositionSizer, no G4 gate, lots remain 0
    @Test
    void s6_g1HardFailure_shortLegSellerPopBelow80pct() {
        Agent1SignalEntity signal = buildSignal(
                Bias.BULLISH, Strength.MILD, Confidence.MEDIUM, VixRegime.HIGH, VIX_HIGH);

        OptionChainData chain = buildChain(SPOT, 24000,
                new BigDecimal("200"), new BigDecimal("200"),
                List.of(
                        strike(24000, OptionType.CE, new BigDecimal("200"), new BigDecimal("0.185"),
                                new BigDecimal("0.50"), new BigDecimal("0.50"))
                ),
                List.of(
                        // pop=0.25 → seller PoP=75% → G1 fails
                        strike(23500, OptionType.PE, new BigDecimal("28"), new BigDecimal("0.200"),
                                new BigDecimal("-0.19"), new BigDecimal("0.25")),
                        strike(23450, OptionType.PE, new BigDecimal("20"), new BigDecimal("0.205"),
                                new BigDecimal("-0.15"), new BigDecimal("0.32"))
                ));

        RecommendationContext ctx = buildCtx(signal, buildProfile(new BigDecimal("500000")),
                HV_14, chain, SPOT, VIX_HIGH, 3);

        engine.execute(ctx);
        logResult("S6 G1 HARD FAILURE shortPut pop=0.25 → sellerPoP=75%", ctx);

        assertThat(ctx.getStrategy()).isEqualTo(Strategy.BULL_PUT_SPREAD);
        assertThat(ctx.getShortLeg().strike()).isEqualTo(23500);
        assertThat(gate(ctx, "G1").passed()).isFalse();
        assertThat(gate(ctx, "G3").passed()).isTrue();   // gap=7% ≤ 15%
        assertThat(ctx.isAllHardGatesPassed()).isFalse();
        // PositionSizer never ran — no G4 gate, lots remain 0
        assertThat(ctx.getGateResults().stream().noneMatch(g -> g.gate().equals("G4"))).isTrue();
        assertThat(ctx.getLots()).isZero();
    }

    // ── Configured test — reads all inputs from JSON ─────────────────────────────

    @Test
    void configuredTest() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("configured-recommendation-scenario.json")) {
            assertThat(is)
                    .as("configured-recommendation-scenario.json not found — create it in "
                            + "agent2-recommendation/src/test/resources/")
                    .isNotNull();

            ScenarioConfig cfg = mapper.readValue(is, ScenarioConfig.class);

            Agent1SignalEntity signal = new Agent1SignalEntity();
            signal.setBias(Bias.valueOf(cfg.bias));
            signal.setStrength(Strength.valueOf(cfg.strength));
            signal.setConfidence(Confidence.valueOf(cfg.confidence));
            signal.setVixRegime(VixRegime.valueOf(cfg.vixRegime));
            signal.setVixLevel(cfg.vixLevel);
            signal.setTimestamp(LocalDateTime.now());
            signal.setExpiryDate(LocalDate.parse(cfg.expiryDate));
            signal.setCompositeScore(BigDecimal.ZERO);
            signal.setConfidenceScore(BigDecimal.ZERO);

            UserProfileEntity profile = buildProfile(cfg.capital);

            List<StrikeData> calls = cfg.calls == null ? List.of() : cfg.calls.stream()
                    .map(s -> new StrikeData(s.strike, OptionType.CE, s.ltp, s.iv, s.delta, s.pop,
                            BigDecimal.valueOf(1000),
                            s.ltp.subtract(BigDecimal.ONE), s.ltp.add(BigDecimal.ONE),
                            "NFO_OPT|NIFTY|" + cfg.expiryDate + "|" + s.strike + "|CE"))
                    .toList();
            List<StrikeData> puts = cfg.puts == null ? List.of() : cfg.puts.stream()
                    .map(s -> new StrikeData(s.strike, OptionType.PE, s.ltp, s.iv, s.delta, s.pop,
                            BigDecimal.valueOf(1000),
                            s.ltp.subtract(BigDecimal.ONE), s.ltp.add(BigDecimal.ONE),
                            "NFO_OPT|NIFTY|" + cfg.expiryDate + "|" + s.strike + "|PE"))
                    .toList();

            OptionChainData chain = (cfg.atmStrike != null)
                    ? new OptionChainData(cfg.spot, LocalDate.parse(cfg.expiryDate),
                            calls, puts, cfg.atmStrike, cfg.atmCallLtp, cfg.atmPutLtp)
                    : null;

            RecommendationContext ctx = new RecommendationContext();
            ctx.setSignal(signal);
            ctx.setUserProfile(profile);
            ctx.setLotSize(cfg.lotSize != null ? cfg.lotSize : 65);
            ctx.setSpot(cfg.spot);
            ctx.setVix(cfg.vix);
            ctx.setHistoricalVolatility(cfg.historicalVolatility);
            ctx.setExpiryDate(LocalDate.parse(cfg.expiryDate));
            ctx.setDte(cfg.dte != null ? cfg.dte : 3);
            ctx.setOptionChainData(chain);

            engine.execute(ctx);
            logResult("CONFIGURED: " + cfg.scenarioName, ctx);

            assertThat(ctx.getStrategy()).isNotNull();
            if (ctx.getStrategy() != Strategy.NO_TRADE && ctx.getStrategy() != Strategy.SKIP) {
                assertThat(ctx.getShortLeg())
                        .as("shortLeg must be set after strike selection for %s", ctx.getStrategy())
                        .isNotNull();
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private Agent1SignalEntity buildSignal(Bias bias, Strength strength, Confidence confidence,
                                            VixRegime vixRegime, BigDecimal vixLevel) {
        Agent1SignalEntity signal = new Agent1SignalEntity();
        signal.setBias(bias);
        signal.setStrength(strength);
        signal.setConfidence(confidence);
        signal.setVixRegime(vixRegime);
        signal.setVixLevel(vixLevel);
        signal.setTimestamp(LocalDateTime.now());
        signal.setExpiryDate(EXPIRY);
        signal.setCompositeScore(BigDecimal.ZERO);
        signal.setConfidenceScore(BigDecimal.ZERO);
        return signal;
    }

    private UserProfileEntity buildProfile(BigDecimal capital) {
        UserProfileEntity profile = new UserProfileEntity();
        profile.setCapital(capital);
        profile.setMaxLossPct(new BigDecimal("1.5"));
        profile.setMinPop(new BigDecimal("80"));
        profile.setMaxPopPoppGap(new BigDecimal("15"));
        profile.setSpreadWidthMin(50);
        profile.setSpreadWidthMax(100);
        return profile;
    }

    private StrikeData strike(int s, OptionType type, BigDecimal ltp, BigDecimal iv,
                               BigDecimal delta, BigDecimal pop) {
        return new StrikeData(s, type, ltp, iv, delta, pop,
                BigDecimal.valueOf(1000),
                ltp.subtract(new BigDecimal("0.50")),
                ltp.add(new BigDecimal("0.50")),
                "NFO_OPT|NIFTY|" + EXPIRY + "|" + s + "|" + type);
    }

    private OptionChainData buildChain(BigDecimal spot, int atmStrike,
                                        BigDecimal atmCallLtp, BigDecimal atmPutLtp,
                                        List<StrikeData> calls, List<StrikeData> puts) {
        return new OptionChainData(spot, EXPIRY, calls, puts, atmStrike, atmCallLtp, atmPutLtp);
    }

    private RecommendationContext buildCtx(Agent1SignalEntity signal, UserProfileEntity profile,
                                            BigDecimal hv, OptionChainData chain,
                                            BigDecimal spot, BigDecimal vix, int dte) {
        RecommendationContext ctx = new RecommendationContext();
        ctx.setSignal(signal);
        ctx.setUserProfile(profile);
        ctx.setLotSize(65);
        ctx.setSpot(spot);
        ctx.setVix(vix);
        ctx.setHistoricalVolatility(hv);
        ctx.setExpiryDate(EXPIRY);
        ctx.setDte(dte);
        ctx.setOptionChainData(chain);
        return ctx;
    }

    private GateResultDto gate(RecommendationContext ctx, String name) {
        return ctx.getGateResults().stream()
                .filter(g -> g.gate().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not found in gate results: "
                        + ctx.getGateResults().stream().map(GateResultDto::gate).toList()));
    }

    private void logResult(String label, RecommendationContext ctx) {
        log.info("─── {} ───", label);
        log.info("  strategy:        {}", ctx.getStrategy());
        log.info("  spreadDirection: {}", ctx.getSpreadDirection());
        log.info("  ivRegime:        {}", ctx.getIvRegime());
        log.info("  expectedMove:    {}", ctx.getExpectedMove());
        log.info("  1.2SdBoundary:   {}", ctx.getOneFourSdBoundary());
        if (ctx.getShortLeg() != null)
            log.info("  shortLeg(PE/CE): {} {} @{} ltp={} delta={} pop={}",
                    ctx.getShortLeg().action(), ctx.getShortLeg().optionType(),
                    ctx.getShortLeg().strike(), ctx.getShortLeg().ltp(),
                    ctx.getShortLeg().delta(), ctx.getShortLeg().pop());
        if (ctx.getLongLeg() != null)
            log.info("  longLeg (PE/CE): {} {} @{} ltp={} delta={} pop={}",
                    ctx.getLongLeg().action(), ctx.getLongLeg().optionType(),
                    ctx.getLongLeg().strike(), ctx.getLongLeg().ltp(),
                    ctx.getLongLeg().delta(), ctx.getLongLeg().pop());
        if (ctx.getShortLeg2() != null)
            log.info("  shortLeg2 (CE):  {} {} @{} ltp={} delta={} pop={}",
                    ctx.getShortLeg2().action(), ctx.getShortLeg2().optionType(),
                    ctx.getShortLeg2().strike(), ctx.getShortLeg2().ltp(),
                    ctx.getShortLeg2().delta(), ctx.getShortLeg2().pop());
        if (ctx.getLongLeg2() != null)
            log.info("  longLeg2  (CE):  {} {} @{} ltp={} delta={} pop={}",
                    ctx.getLongLeg2().action(), ctx.getLongLeg2().optionType(),
                    ctx.getLongLeg2().strike(), ctx.getLongLeg2().ltp(),
                    ctx.getLongLeg2().delta(), ctx.getLongLeg2().pop());
        log.info("  gates: {}",
                ctx.getGateResults().stream()
                        .map(g -> g.gate() + "=" + (g.passed() ? "PASS" : "FAIL")
                                + "(val=" + g.value() + ")")
                        .toList());
        log.info("  allGatesPassed:  {}", ctx.isAllHardGatesPassed());
        log.info("  lots:            {}", ctx.getLots());
        log.info("  maxProfitTotal:  {}", ctx.getMaxProfitTotal());
        log.info("  realLossTotal:   {}", ctx.getRealExpectedLossTotal());
        log.info("  roc:             {}%", ctx.getRoc());
        log.info("  rocAnnualised:   {}%", ctx.getRocAnnualised());
        log.info("  netDelta:        {}", ctx.getNetDelta());
    }

    // ── JSON config POJO for configuredTest ──────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ScenarioConfig {
        public String     scenarioName;

        // Signal fields
        public String     bias;
        public String     strength;
        public String     confidence;
        public String     vixRegime;
        public BigDecimal vixLevel;

        // Market context
        public BigDecimal spot;
        public BigDecimal vix;
        public BigDecimal historicalVolatility;
        public Integer    dte;
        public String     expiryDate;
        public Integer    lotSize;
        public BigDecimal capital;

        // Option chain
        public Integer    atmStrike;
        public BigDecimal atmCallLtp;
        public BigDecimal atmPutLtp;
        public List<ScenarioStrike> calls;
        public List<ScenarioStrike> puts;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class ScenarioStrike {
            public int        strike;
            public BigDecimal ltp;
            public BigDecimal iv;
            public BigDecimal delta;
            public BigDecimal pop;
        }
    }
}
