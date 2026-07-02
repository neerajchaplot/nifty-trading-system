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
 * Unit tests for CreditSpreadMonitorStrategy — Bear Call Spread (CE short) path.
 *
 * Bear Call Spread: SELL CE at higher strike, BUY CE at even higher strike.
 * Danger direction is Nifty RISING (opposite to Bull Put Spread).
 *
 * Setup:
 *   CE SELL 24000 (short) / CE BUY 24100 (long)
 *   Threshold levels (CE short, Nifty RISING = danger):
 *     T1 watch:    spot ≥ 23900  (100 pts below CE short)
 *     T2 readjust: spot ≥ 23950  (50 pts below CE short)
 *     T3 exit:     spot ≥ 24000  (at or above CE short — ITM breach)
 *
 * Key distinction from Bull Put Spread: breach is triggered by Nifty RISING
 * to or above the short CE strike (not falling below short PE strike).
 */
class BearCallSpreadMonitorStrategyTest {

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

    // ── 1. Healthy trade — spot far below CE short, high PoP → HOLD ──────────

    @Test
    void evaluate_spotFarBelowCeShort_returnsHold() {
        // spot=23500 — far below CE short 24000, CE spread is comfortably OTM
        MonitorEvaluationContext ctx = buildContext(23500.0, 17.5, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.HOLD);
    }

    // ── 2. CE short strike breached (spot ABOVE CE short) → EXIT ─────────────

    @Test
    void evaluate_spotAtCeShortStrike_returnsExit() {
        // spot = 24000 exactly at CE short — option is now ATM, breach triggered
        MonitorEvaluationContext ctx = buildContext(24000.0, 20.0, 3);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
    }

    @Test
    void evaluate_spotAboveCeShortStrike_returnsExit() {
        // spot = 24100 — well through CE short 24000 (long CE is now at risk too)
        MonitorEvaluationContext ctx = buildContext(24100.0, 22.0, 2);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
    }

    @Test
    void evaluate_spotSlightlyAboveCeShort_returnsExit() {
        // spot = 24010 — just above CE short 24000
        MonitorEvaluationContext ctx = buildContext(24010.0, 21.0, 4);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
    }

    // ── 3. Spot below CE short — no breach even when very close ──────────────

    @Test
    void evaluate_spotJustBelowCeShortStrike_noStrikeBreach() {
        // spot = 23999 — 1 point below CE short 24000. Should NOT be a breach.
        // PoP at this level will be very low → strategy should EXIT or READJUST based on PoP
        MonitorEvaluationContext ctx = buildContext(23999.0, 21.0, 2);
        EvaluationResult result = strategy.evaluate(ctx);
        // Must NOT be T3_SHORT_STRIKE_BREACH — breach requires spot ≥ 24000
        if (result.thresholdHit() == ThresholdHit.T3_SHORT_STRIKE_BREACH) {
            assertThat(result.thresholdHit()).as("23999 should not breach CE short at 24000")
                    .isNotEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
        }
        // Will likely be EXIT via PoP since spot is ATM
        assertThat(result.action()).isIn(MonitorAction.EXIT, MonitorAction.READJUST);
    }

    // ── 4. VIX extreme → PAUSE (direction-independent) ───────────────────────

    @Test
    void evaluate_vixExtreme_returnsPause() {
        MonitorEvaluationContext ctx = buildContext(23500.0, 25.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.PAUSE);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.VIX_EXTREME_PAUSE);
    }

    // ── 5. Expiry day (DTE=0) — CE proximity logic ───────────────────────────

    @Test
    void evaluate_expiryDay_spotWithinExitBuffer_returnsExit() {
        // exitBuffer = 75 pts (default). spot = 23930 → distance from CE short 24000 = 70 pts < 75
        // isWithinProximityBuffer for CE: spot ≥ shortStrike - buffer = 24000 - 75 = 23925
        // 23930 ≥ 23925 → within exit buffer → EXIT
        MonitorEvaluationContext ctx = buildContext(23930.0, 18.0, 0);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.EXPIRY_DAY_PROXIMITY_EXIT);
    }

    @Test
    void evaluate_expiryDay_spotWithinWatchBuffer_returnsWatch() {
        // watchBuffer = 150 pts, exitBuffer = 75 pts.
        // spot = 23870: distance from CE short 24000 = 130 pts.
        // 23870 ≥ 24000 - 150 = 23850 (in watch buffer) AND 23870 < 24000 - 75 = 23925 (outside exit buffer)
        MonitorEvaluationContext ctx = buildContext(23870.0, 18.0, 0);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.WATCH);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.EXPIRY_DAY_PROXIMITY_WATCH);
    }

    @Test
    void evaluate_expiryDay_spotSafelyBelowCeShort_returnsHold() {
        // spot = 23700: distance from CE short 24000 = 300 pts > watchBuffer (150) → HOLD
        MonitorEvaluationContext ctx = buildContext(23700.0, 17.0, 0);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.HOLD);
    }

    @Test
    void evaluate_expiryDay_ceShortBreach_returnsExit() {
        // spot = 24050 on expiry day — CE short breached even on expiry day
        MonitorEvaluationContext ctx = buildContext(24050.0, 19.0, 0);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.T3_SHORT_STRIKE_BREACH);
    }

    // ── 6. Inverse of Bull Put — rising spot does NOT trigger PE-breach logic ─

    @Test
    void evaluate_spotFarAbovePeStrike_doesNotTriggerBreach() {
        // For a Bear Call Spread, there's no "PE short" in the config.
        // A high spot is actually GOOD (CE is OTM when spot is below CE strike).
        // spot = 23500 — completely safe for a CE spread with short at 24000
        MonitorEvaluationContext ctx = buildContext(23500.0, 17.0, 5);
        EvaluationResult result = strategy.evaluate(ctx);
        assertThat(result.action()).isEqualTo(MonitorAction.HOLD);
        assertThat(result.thresholdHit()).isEqualTo(ThresholdHit.NONE);
    }

    // ── Test data builder ─────────────────────────────────────────────────────

    /**
     * Bear Call Spread config:
     *   CE SELL 24000 (short), CE BUY 24100 (long)
     *   Credit received: ~15 pts per unit
     *   Thresholds: T1 watch at spot≥23900, T2 readjust at spot≥23950
     *
     * LTP values are set to be low (spread is far OTM when spot is far below 24000)
     * to produce HOLD scenarios. For breach tests, spot itself determines the outcome.
     */
    private MonitorEvaluationContext buildContext(double spot, double vix, int dte) {
        TradeLegDto shortLeg = new TradeLegDto(
                OptionType.CE, 24000, new BigDecimal("18.00"),
                LegAction.SELL, new BigDecimal("0.17"), new BigDecimal("0.83"),
                "NFO_OPT|NIFTY|2026-06-24|24000|CE");
        TradeLegDto longLeg = new TradeLegDto(
                OptionType.CE, 24100, new BigDecimal("10.00"),
                LegAction.BUY,  new BigDecimal("0.13"), new BigDecimal("0.87"),
                "NFO_OPT|NIFTY|2026-06-24|24100|CE");

        // CE short 24000 — danger is spot RISING:
        //   T1 watch:    spot ≥ shortStrike - 100 = 23900
        //   T2 readjust: spot ≥ shortStrike - 50  = 23950
        //   T3 exit:     at shortStrike = 24000
        MonitorThresholdsDto thresholds = MonitorThresholdsDto.twoLeg(
                new BigDecimal("23900"),   // t1WatchNiftyLevel (100 pts below CE short)
                new BigDecimal("23950"),   // t2ReadjustNiftyLevel (50 pts below CE short)
                new BigDecimal("24000"),   // t3ExitNiftyLevel (at CE short)
                new BigDecimal("9750"),    // t2LossThreshold
                new BigDecimal("19500")); // t3LossThreshold

        MonitorConfigDto config = MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BEAR_CALL_SPREAD, SpreadDirection.CREDIT,
                shortLeg, longLeg,
                new BigDecimal("8.00"), // actualNetPremiumPerUnit: 18-10=8
                10, 65,                  // lots=10, lotSize=65
                new BigDecimal("5200"),  // maxProfitTotal: 8 × 650
                new BigDecimal("58500"), // actualMaxLossTotal: (100-8) × 650
                false, null,
                thresholds,
                LocalDate.now().plusDays(dte), dte);

        // Short CE LTP starts low (8) — spread is OTM. IV from config not passed separately.
        BigDecimal shortLtp = new BigDecimal("8.00");
        BigDecimal longLtp  = new BigDecimal("4.00");
        BigDecimal shortIv  = new BigDecimal("0.16"); // 16% IV for CE short

        LiveMarketSnapshot liveData = new LiveMarketSnapshot(
                new BigDecimal(spot), new BigDecimal(vix), shortLtp, longLtp, shortIv);

        return new MonitorEvaluationContext(config, liveData, dte, null, null);
    }
}
