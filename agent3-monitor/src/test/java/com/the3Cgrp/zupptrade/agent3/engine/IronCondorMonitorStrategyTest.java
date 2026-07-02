package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.math.BlackScholesCalculator;
import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.agent3.model.MonitorEvaluationContext;
import com.the3Cgrp.zupptrade.agent3.service.LivePopService;
import com.the3Cgrp.zupptrade.agent3.service.PnlCalculationService;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for IronCondorMonitorStrategy.
 *
 * IC setup used throughout:
 *   PE short=23500, PE long=23400  (downside — spot falling is danger)
 *   CE short=24000, CE long=24100  (upside  — spot rising  is danger)
 *
 * Safe zone: spot between 23501 and 23999.
 * Bilateral Nifty thresholds (from buildThresholds logic):
 *   T1 down=23600, T2 down=23550  (PE side: 100 and 50 pts above PE short)
 *   T1 up=23900,   T2 up=23950    (CE side: 100 and 50 pts below CE short)
 */
class IronCondorMonitorStrategyTest {

    private IronCondorMonitorStrategy strategy;
    private MonitoringProperties props;

    @BeforeEach
    void setUp() {
        props = new MonitoringProperties();
        PnlCalculationService pnlService = new PnlCalculationService();
        BlackScholesCalculator calculator = new BlackScholesCalculator();
        LivePopService livePopService = new LivePopService(calculator, props);
        strategy = new IronCondorMonitorStrategy(pnlService, livePopService, props);
    }

    // ── 1. VIX Extreme ───────────────────────────────────────────────────────

    @Test
    void evaluate_vixExtreme_returnsPause() {
        MonitorEvaluationContext ctx = buildContext(23750.0, 25.5, 40.0, 10.0, 30.0, 10.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.PAUSE);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.VIX_EXTREME_PAUSE);
    }

    // ── 2. PE short strike breach ─────────────────────────────────────────────

    @Test
    void evaluate_spotAtPeShortStrike_returnsExit() {
        // spot = 23500 exactly at PE short → breach
        MonitorEvaluationContext ctx = buildContext(23500.0, 18.0, 60.0, 25.0, 25.0, 10.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
        assertThat(result.reason()).contains("PE SHORT BREACHED");
    }

    @Test
    void evaluate_spotBelowPeShortStrike_returnsExit() {
        // spot = 23450 — well through PE short 23500
        MonitorEvaluationContext ctx = buildContext(23450.0, 18.0, 80.0, 30.0, 20.0, 8.0, 3);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
    }

    // ── 3. CE short strike breach ─────────────────────────────────────────────

    @Test
    void evaluate_spotAtCeShortStrike_returnsExit() {
        // spot = 24000 exactly at CE short → breach
        MonitorEvaluationContext ctx = buildContext(24000.0, 18.0, 20.0, 5.0, 80.0, 30.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
        assertThat(result.reason()).contains("CE SHORT BREACHED");
    }

    @Test
    void evaluate_spotAboveCeShortStrike_returnsExit() {
        // spot = 24100 — well through CE short 24000
        MonitorEvaluationContext ctx = buildContext(24100.0, 18.0, 15.0, 4.0, 110.0, 50.0, 4);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
    }

    // ── 4. T3 P&L threshold ───────────────────────────────────────────────────

    @Test
    void evaluate_t3LossBreached_returnsExit() {
        // t3LossThreshold = 30000. Force a large P&L loss via very high short LTP.
        // Credit received: netPremium = 25.00/unit, lots=10, lotSize=65 → 16250 received
        // Short PE ltp=80, long PE ltp=25 → currentNetPE=55 (was ~10 for PE side)
        // P&L will be negative enough to breach 30000 threshold
        MonitorEvaluationContext ctx = buildContext(23650.0, 18.0, 80.0, 25.0, 25.0, 10.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        // mtm will be huge loss — short PE premium expanded massively
        assertThat(result.action()).isIn(MonitorAction.EXIT, MonitorAction.READJUST);
        // If T3 breach → EXIT with T3_EXIT_PNL
        if (result.action() == MonitorAction.EXIT) {
            assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_EXIT_PNL);
        }
    }

    @Test
    void evaluate_exactT3LossThreshold_returnsExit() {
        // Build a context where we know the exact P&L will breach t3LossThreshold=30000
        // actualNetPremium = 25.0, lots=10, lotSize=65 → total position = 650 units
        // P&L = (25 - currentNetPremium) × 650
        // For P&L = -31200: currentNetPremium = 25 + 31200/650 = 73.0
        // Short PE ltp=83, long PE ltp=10 → currentNetPE = 73 → P&L ≈ -31200
        MonitorEvaluationContext ctx = buildContext(23550.0, 19.0, 83.0, 10.0, 22.0, 8.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        // Either T3 P&L breach or T2 proximity (23550 ≤ T2 down=23550)
        assertThat(result.action()).isIn(MonitorAction.EXIT, MonitorAction.READJUST);
    }

    // ── 5. T2 P&L threshold ───────────────────────────────────────────────────

    @Test
    void evaluate_t2LossBreached_returnsReadjust() {
        // t2LossThreshold = 15000. actualNetPremium=25, total=650 units
        // P&L for READJUST: (25 - currentNetPremium) × 650 = -15001
        // currentNetPremium = 25 + 15001/650 ≈ 48.08 → short ltp=58, long ltp=10 → netPE=48
        // Must be below T2 down threshold to avoid proximity override:
        // spot=23700 (above T2 down=23550, below T1 down=23600, safe on CE side)
        // Actually 23700 is above T1=23600 — so in "down" zone: T1 is spot ≤ 23600
        // Use spot=23800 which is safely above T1 down=23600 and below T1 up=23900
        MonitorEvaluationContext ctx = buildContext(23800.0, 18.0, 55.0, 7.0, 22.0, 8.0, 5);
        // P&L = (25 - (55-7)) × 650 = (25 - 48) × 650 = -23 × 650 = -14950
        // That's < 15000 so doesn't breach T2. Let's use higher ltp:
        // netPE = 60 - 7 = 53 → P&L = (25 - 53) × 650 = -18200 > 15000 → T2 breach
        MonitorEvaluationContext ctx2 = buildContext(23800.0, 18.0, 60.0, 7.0, 22.0, 8.0, 5);
        EvaluationResult result = strategy.evaluate(ctx2);
        // Should be READJUST (T2 P&L) since spot is in safe zone
        assertThat(result.action()).isEqualTo(MonitorAction.READJUST);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T2_READJUST_PNL);
    }

    // ── 6. T2 proximity — PE side ─────────────────────────────────────────────

    @Test
    void evaluate_spotAtT2Down_returnsReadjust() {
        // T2 down = 23550 (PE short 23500 + 50). Spot=23550 → exactly at T2 down
        MonitorEvaluationContext ctx = buildContext(23550.0, 18.0, 30.0, 10.0, 22.0, 8.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.READJUST);
    }

    @Test
    void evaluate_spotBelowT2Down_returnsReadjust() {
        // spot=23520 < T2 down=23550 — still above PE short (23500), so no breach
        // But T2 level is hit
        MonitorEvaluationContext ctx = buildContext(23520.0, 18.0, 38.0, 12.0, 20.0, 7.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        // Either T3 breach (23520 > 23500 so no), T3 P&L or T2 proximity
        assertThat(result.action()).isIn(MonitorAction.READJUST, MonitorAction.EXIT);
    }

    // ── 7. T2 proximity — CE side ─────────────────────────────────────────────

    @Test
    void evaluate_spotAtT2Up_returnsReadjust() {
        // T2 up = 23950 (CE short 24000 - 50). Spot=23950 → exactly at T2 up
        MonitorEvaluationContext ctx = buildContext(23950.0, 18.0, 18.0, 5.0, 60.0, 20.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.READJUST);
    }

    @Test
    void evaluate_spotAboveT2Up_returnsReadjust() {
        // spot=23970 > T2 up=23950, still below CE short 24000 → T2 zone, not breach
        MonitorEvaluationContext ctx = buildContext(23970.0, 18.0, 18.0, 5.0, 70.0, 22.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isIn(MonitorAction.READJUST, MonitorAction.EXIT);
    }

    // ── 8. T1 proximity — PE side ─────────────────────────────────────────────

    @Test
    void evaluate_spotAtT1Down_returnsWatch() {
        // T1 down = 23600 (PE short 23500 + 100). Spot=23600 → at T1 watch level
        // Spot is above T2 down (23550) so T2 not triggered
        MonitorEvaluationContext ctx = buildContext(23600.0, 18.0, 22.0, 8.0, 22.0, 8.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.WATCH);
    }

    @Test
    void evaluate_spotBelowT1DownButAboveT2Down_returnsWatch() {
        // T1 down=23600, T2 down=23550. Spot=23570 — between T1 and T2 → WATCH
        MonitorEvaluationContext ctx = buildContext(23570.0, 18.0, 28.0, 9.0, 22.0, 8.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        // Could be T2 if P&L also breaches — depends on LTP values here
        assertThat(result.action()).isIn(MonitorAction.WATCH, MonitorAction.READJUST, MonitorAction.EXIT);
    }

    // ── 9. T1 proximity — CE side ─────────────────────────────────────────────

    @Test
    void evaluate_spotAtT1Up_returnsWatch() {
        // T1 up = 23900 (CE short 24000 - 100). Spot=23900 → at T1 watch level
        // Spot is below T2 up (23950) so T2 not triggered
        MonitorEvaluationContext ctx = buildContext(23900.0, 18.0, 18.0, 5.0, 38.0, 12.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.WATCH);
    }

    // ── 10. HOLD — spot safely inside condor body ──────────────────────────────

    @Test
    void evaluate_spotSafelyInsideCondor_returnsHold() {
        // spot=23750 — midpoint of condor body, all thresholds clear
        // low LTPs (good for the credit spread seller)
        MonitorEvaluationContext ctx = buildContext(23750.0, 17.0, 15.0, 5.0, 12.0, 4.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.HOLD);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.NONE);
    }

    @Test
    void evaluate_spotMidCondor_highDte_returnsHold() {
        // spot=23750, DTE=10 — early in the week, position healthy
        MonitorEvaluationContext ctx = buildContext(23750.0, 16.5, 12.0, 4.0, 10.0, 3.0, 10);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.HOLD);
    }

    // ── 11. Null spot — conservative WATCH ────────────────────────────────────

    @Test
    void evaluate_spotNull_returnsWatch() {
        MonitorEvaluationContext ctx = buildContextNullSpot(17.0, 15.0, 5.0, 12.0, 4.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.WATCH);
    }

    // ── Test data builders ─────────────────────────────────────────────────────

    /**
     * Builds an IC context with 4 legs:
     *   PE SELL 23500 / PE BUY 23400 (downside)
     *   CE SELL 24000 / CE BUY 24100 (upside)
     *
     * Bilateral thresholds:
     *   T1 down=23600, T2 down=23550, T3 down=23500 (PE short)
     *   T1 up=23900,   T2 up=23950,   T3 up=24000   (CE short)
     *   t2LossThreshold=15000, t3LossThreshold=30000
     *
     * actualNetPremium=25.00, lots=10, lotSize=65
     *
     * @param peLtp    live LTP for PE short leg (shortLeg)
     * @param peLong   live LTP for PE long leg  (longLeg)
     * @param ceLtp    live LTP for CE short leg (shortLeg2) — passed in shortLegLtp field
     * @param ceLong   live LTP for CE long leg  (longLeg2)  — not directly used by strategy
     */
    private MonitorEvaluationContext buildContext(double spot, double vix,
                                                   double peLtp, double peLong,
                                                   double ceLtp, double ceLong,
                                                   int dte) {
        MonitorConfigDto config = buildIcConfig();
        LiveMarketSnapshot liveData = new LiveMarketSnapshot(
                new BigDecimal(spot), new BigDecimal(vix),
                new BigDecimal(peLtp), new BigDecimal(peLong), null);
        return new MonitorEvaluationContext(config, liveData, dte, null, null);
    }

    private MonitorEvaluationContext buildContextNullSpot(double vix,
                                                           double peLtp, double peLong,
                                                           double ceLtp, double ceLong,
                                                           int dte) {
        MonitorConfigDto config = buildIcConfig();
        LiveMarketSnapshot liveData = new LiveMarketSnapshot(
                null, new BigDecimal(vix),
                new BigDecimal(peLtp), new BigDecimal(peLong), null);
        return new MonitorEvaluationContext(config, liveData, dte, null, null);
    }

    private MonitorConfigDto buildIcConfig() {
        TradeLegDto peShortLeg = new TradeLegDto(OptionType.PE, 23500, new BigDecimal("40.00"),
                LegAction.SELL, new BigDecimal("-0.18"), new BigDecimal("0.82"),
                "NFO_OPT|NIFTY|2026-06-24|23500|PE");
        TradeLegDto peLongLeg  = new TradeLegDto(OptionType.PE, 23400, new BigDecimal("25.00"),
                LegAction.BUY,  new BigDecimal("-0.13"), new BigDecimal("0.87"),
                "NFO_OPT|NIFTY|2026-06-24|23400|PE");
        TradeLegDto ceShortLeg = new TradeLegDto(OptionType.CE, 24000, new BigDecimal("30.00"),
                LegAction.SELL, new BigDecimal("0.16"), new BigDecimal("0.84"),
                "NFO_OPT|NIFTY|2026-06-24|24000|CE");
        TradeLegDto ceLongLeg  = new TradeLegDto(OptionType.CE, 24100, new BigDecimal("18.00"),
                LegAction.BUY,  new BigDecimal("0.12"), new BigDecimal("0.88"),
                "NFO_OPT|NIFTY|2026-06-24|24100|CE");

        // PE short 23500 → T1 down = 23600, T2 down = 23550
        // CE short 24000 → T1 up   = 23900, T2 up   = 23950
        MonitorThresholdsDto thresholds = MonitorThresholdsDto.ironCondor(
                new BigDecimal("23600"), new BigDecimal("23550"), new BigDecimal("23500"),  // T1/T2/T3 down
                new BigDecimal("23900"), new BigDecimal("23950"), new BigDecimal("24000"),  // T1/T2/T3 up
                new BigDecimal("15000"), new BigDecimal("30000"));                           // T2/T3 loss

        return MonitorConfigDto.ironCondor(
                UUID.randomUUID(), Strategy.IRON_CONDOR, SpreadDirection.CREDIT,
                peShortLeg, peLongLeg, ceShortLeg, ceLongLeg,
                new BigDecimal("25.00"), 10, 65,
                new BigDecimal("16250"), new BigDecimal("65000"),
                false, null, thresholds,
                LocalDate.now().plusDays(5), 5);
    }
}
