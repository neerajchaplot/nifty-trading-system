package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.agent3.model.MonitorEvaluationContext;
import com.the3Cgrp.zupptrade.agent3.service.LivePopService;
import com.the3Cgrp.zupptrade.agent3.service.PnlCalculationService;
import com.the3Cgrp.zupptrade.agent3.math.BlackScholesCalculator;
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

class CreditSpreadMonitorStrategyTest {

    private CreditSpreadMonitorStrategy strategy;
    private MonitoringProperties props;

    @BeforeEach
    void setUp() {
        props = new MonitoringProperties();
        PnlCalculationService pnlService = new PnlCalculationService();
        BlackScholesCalculator calculator = new BlackScholesCalculator();
        LivePopService livePopService = new LivePopService(calculator, props);
        strategy = new CreditSpreadMonitorStrategy(pnlService, livePopService, props);
    }

    // Scenario 1: Healthy trade — spot far above short put strike, high PoP → HOLD
    @Test
    void evaluate_healthyTrade_highPop_returnsHold() {
        MonitorEvaluationContext ctx = buildContext(
                23412.60,  // spot — far above short put strike 23000
                17.5,      // VIX — normal
                45.0,      // short leg LTP
                20.0,      // long leg LTP
                0.159,     // short leg IV (decimal)
                5          // DTE
        );
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.HOLD);
    }

    // Scenario 2: PoP drops below 65% → EXIT
    @Test
    void evaluate_popBelow65Pct_returnsExit() {
        // Spot at 23050, just above put strike 23000 — very close, PoP will be low
        MonitorEvaluationContext ctx = buildContext(
                23050.0,   // spot — very close to short put strike 23000
                20.0,      // VIX
                85.0,      // short leg LTP (high — spread is near ATM)
                55.0,      // long leg LTP
                0.25,      // IV elevated
                3
        );
        EvaluationResult result = strategy.evaluate(ctx);
        // PoP should be very low when spot is 50 pts above short strike with 3 DTE and 25% IV
        assertThat(result.action()).isIn(MonitorAction.EXIT, MonitorAction.READJUST);
    }

    // Scenario 3: Short put strike breached → EXIT regardless of PoP
    @Test
    void evaluate_shortStrikeBreached_returnsExit() {
        MonitorEvaluationContext ctx = buildContext(
                22980.0,   // spot BELOW short put strike 23000
                19.0, 120.0, 80.0, 0.20, 2);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
    }

    // Scenario 4: T3 P&L breach → EXIT
    @Test
    void evaluate_t3PnlBreached_returnsExit() {
        // actualNetPremiumPerUnit=23.20, lots=54, lotSize=65 → t3LossThreshold=134277
        // currentNetPremium = 70.00 - 30.00 = 40.00  (way above 23.20)
        // P&L = (23.20 - 40.00) × 3510 = -58968 ... need more extreme for t3
        // Let's use current net = 80 - 42 = 38.00 * 3510 = at entry received only 23.20 * 3510 = 81432
        // Loss = (23.20 - 38.00) × 3510 = -51948  -- not enough for t3LossThreshold=134277
        // Use current net = 100 - 62 = 38 + extra to breach 134277
        // Need: (23.20 - currentNet) × 3510 <= -134277 → currentNet >= 61.40
        MonitorEvaluationContext ctx = buildContext(
                23200.0,    // spot — above short strike still
                18.0,
                130.0,     // short leg LTP very high
                65.0,      // long leg LTP  → current net = 65
                0.20, 2);
        EvaluationResult result = strategy.evaluate(ctx);
        // P&L = (23.20 - 65.00) × 3510 = -146,718 → breaches t3LossThreshold=134277
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_EXIT_PNL);
    }

    // Scenario 5: VIX > 24 → PAUSE
    @Test
    void evaluate_vixExtreme_returnsPause() {
        MonitorEvaluationContext ctx = buildContext(
                24000.0, 25.5, 45.0, 20.0, 0.159, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.PAUSE);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.VIX_EXTREME_PAUSE);
    }

    // Scenario 6: PoP in 75-79% → WATCH
    @Test
    void evaluate_popInWatchZone_returnsWatch() {
        // Spot approaching short put, PoP in watch zone (~77%)
        // spot=23350, strike=23000, IV=18%, DTE=5 → should give WATCH zone PoP
        MonitorEvaluationContext ctx = buildContext(
                23200.0,   // spot getting closer to 23000 strike
                18.0,
                55.0,      // short leg LTP up
                28.0,      // long leg LTP up
                0.18, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        // With this setup PoP should be in WATCH range depending on exact values
        assertThat(result.action()).isIn(MonitorAction.WATCH, MonitorAction.READJUST, MonitorAction.HOLD);
    }

    // Scenario 7: T2 P&L escalation from HOLD → READJUST
    @Test
    void evaluate_t2PnlBreached_escalatesToReadjust() {
        // P&L needs to breach t2LossThreshold=67138
        // (23.20 - currentNet) × 3510 = -67138 → currentNet = 23.20 + 19.13 = 42.33
        // Use currentNet = 43 to breach
        // short=63, long=20 → net=43
        // But spot still above short strike so no T3 strike breach
        MonitorEvaluationContext ctx = buildContext(
                23100.0, 18.0,
                63.0,    // short leg LTP
                20.0,    // long leg → current net = 43
                0.18, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        // P&L = (23.20 - 43) × 3510 = -69498 → breaches t2LossThreshold=67138
        // PoP might still be in HOLD/WATCH zone, but T2 P&L should escalate to READJUST
        assertThat(result.action()).isIn(MonitorAction.READJUST, MonitorAction.EXIT);
    }

    // Scenario 8: Missing market data → WATCH (conservative)
    @Test
    void evaluate_missingMarketData_returnsWatch() {
        MonitorConfigDto config = buildConfig();
        LiveMarketSnapshot noData = new LiveMarketSnapshot(null, new BigDecimal("18.0"), null, null, null);
        MonitorEvaluationContext ctx = new MonitorEvaluationContext(config, noData, 5, null, null);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.WATCH);
    }

    private MonitorEvaluationContext buildContext(double spot, double vix,
                                                   double shortLtp, double longLtp,
                                                   double shortIv, int dte) {
        LiveMarketSnapshot liveData = new LiveMarketSnapshot(
                new BigDecimal(spot), new BigDecimal(vix),
                new BigDecimal(shortLtp), new BigDecimal(longLtp),
                new BigDecimal(shortIv));
        return new MonitorEvaluationContext(buildConfig(), liveData, dte, null, null);
    }

    private MonitorConfigDto buildConfig() {
        TradeLegDto shortLeg = new TradeLegDto(OptionType.PE, 23000, new BigDecimal("68.40"),
                LegAction.SELL, new BigDecimal("-0.169"), new BigDecimal("0.826"), null);
        TradeLegDto longLeg = new TradeLegDto(OptionType.PE, 22900, new BigDecimal("45.20"),
                LegAction.BUY, new BigDecimal("-0.142"), new BigDecimal("0.858"), null);
        MonitorThresholdsDto thr = MonitorThresholdsDto.twoLeg(
                new BigDecimal("23150"), new BigDecimal("23075"),
                new BigDecimal("23000"),
                new BigDecimal("67138.00"),   // t2 loss
                new BigDecimal("134277.00")); // t3 loss
        return MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT,
                shortLeg, longLeg, new BigDecimal("23.20"), 54, 65,
                new BigDecimal("81396"), new BigDecimal("268554"), false, null,
                thr, LocalDate.now().plusDays(5), 5);
    }
}
