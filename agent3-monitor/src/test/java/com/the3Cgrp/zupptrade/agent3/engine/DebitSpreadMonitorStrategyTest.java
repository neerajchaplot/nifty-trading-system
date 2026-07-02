package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.agent3.model.MonitorEvaluationContext;
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

class DebitSpreadMonitorStrategyTest {

    private DebitSpreadMonitorStrategy strategy;

    @BeforeEach
    void setUp() {
        MonitoringProperties props = new MonitoringProperties();
        strategy = new DebitSpreadMonitorStrategy(new PnlCalculationService(), props);
    }

    // Scenario: Theta exit — DTE ≤ 2 with no profit → EXIT
    @Test
    void evaluate_dteLe2_noProfitYet_returnsTheta_Exit() {
        MonitorEvaluationContext ctx = buildContext(24000.0, 17.0, 18.0, 28.0, 1);
        // currentNetPremium = 28 - 18 = 10. Premium paid = 25. Loss = -9750.
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.DEBIT_THETA_EXIT);
    }

    // Scenario: T1 profit level reached → WATCH
    @Test
    void evaluate_t1ProfitLevelReached_returnsWatch() {
        // Bull call spread, t1WatchNiftyLevel=24100, spot=24120 → hit!
        MonitorEvaluationContext ctx = buildContext(24120.0, 17.0, 15.0, 55.0, 4);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.WATCH);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.DEBIT_T1_PROFIT_NIFTY);
    }

    // Scenario: T2 profit level reached → EXIT for profit
    @Test
    void evaluate_t2ProfitLevelReached_returnsExit() {
        // t2ReadjustNiftyLevel=24200, spot=24250 → hit!
        MonitorEvaluationContext ctx = buildContext(24250.0, 17.0, 12.0, 70.0, 4);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.DEBIT_T2_PROFIT_NIFTY);
    }

    // Scenario: T3 loss — 50% of premium lost → EXIT
    @Test
    void evaluate_t3LossBreached_returnsExit() {
        // Paid net premium = 25. t2LossThreshold = 8125 (50% of 25 × 650).
        // currentNetPremium = long(8) - short(20) = -12 → that is debit = 12, but we paid 25
        // P&L = (12 - 25) × 650 = -8450 → breaches 8125
        MonitorEvaluationContext ctx = buildContext(23800.0, 17.0, 20.0, 8.0, 4);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.DEBIT_T3_LOSS_PNL);
    }

    // Scenario: VIX extreme → PAUSE
    @Test
    void evaluate_vixExtreme_returnsPause() {
        MonitorEvaluationContext ctx = buildContext(24000.0, 25.0, 18.0, 40.0, 4);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.PAUSE);
    }

    // Scenario: Normal — waiting for directional move → HOLD
    @Test
    void evaluate_normalConditions_returnsHold() {
        // No profit target hit, no loss breach, DTE=4
        MonitorEvaluationContext ctx = buildContext(24050.0, 17.0, 16.0, 36.0, 4);
        // currentNetPremium = 36 - 16 = 20. Paid = 25. P&L = (20-25)×650 = -3250 < 8125 threshold
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.HOLD);
    }

    private MonitorEvaluationContext buildContext(double spot, double vix,
                                                   double shortLtp, double longLtp, int dte) {
        // Bull Call Spread: longLeg = lower strike CE (BUY), shortLeg = upper strike CE (SELL)
        TradeLegDto shortLeg = new TradeLegDto(OptionType.CE, 24000, new BigDecimal("20.00"),
                LegAction.SELL, new BigDecimal("0.35"), new BigDecimal("0.65"), null);
        TradeLegDto longLeg = new TradeLegDto(OptionType.CE, 23800, new BigDecimal("45.00"),
                LegAction.BUY, new BigDecimal("0.55"), new BigDecimal("0.45"), null);
        MonitorThresholdsDto thr = MonitorThresholdsDto.twoLeg(
                new BigDecimal("24100"),  // T1 profit target Nifty level
                new BigDecimal("24200"),  // T2 profit target Nifty level
                null,
                new BigDecimal("8125.00"),  // 50% of premium paid: 25 × 10 × 65 × 0.5
                null);
        MonitorConfigDto config = MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT,
                shortLeg, longLeg, new BigDecimal("25.00"), 10, 65,
                new BigDecimal("13000"), new BigDecimal("16250"), false, null,
                thr, LocalDate.now().plusDays(dte), dte);

        LiveMarketSnapshot liveData = new LiveMarketSnapshot(
                new BigDecimal(spot), new BigDecimal(vix),
                new BigDecimal(shortLtp), new BigDecimal(longLtp), null);
        return new MonitorEvaluationContext(config, liveData, dte, null, null);
    }
}
