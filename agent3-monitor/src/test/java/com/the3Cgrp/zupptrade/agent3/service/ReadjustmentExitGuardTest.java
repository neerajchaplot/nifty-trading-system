package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.shared.enums.MonitorAction;
import com.the3Cgrp.zupptrade.shared.enums.ThresholdHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for MonitorEvaluationService.applyReadjustmentExitGuard.
 *
 * Rule under test: a READJUST is downgraded to EXIT when
 *   (a) the trade was itself opened as a readjustment re-entry (one readjustment only), or
 *   (b) it is expiry day (DTE=0) — no trade is readjusted on expiry, only exited.
 * Every other case passes through unchanged.
 */
class ReadjustmentExitGuardTest {

    private static EvaluationResult result(MonitorAction action) {
        return new EvaluationResult(action, ThresholdHit.POP_READJUST, "PoP=63% below T2",
                new BigDecimal("12.50"), new BigDecimal("-4200.00"), new BigDecimal("0.63"),
                Map.of("spot", new BigDecimal("23600")));
    }

    @Test
    @DisplayName("Readjustment-born trade: READJUST → EXIT (exit-only, never readjusted again)")
    void readjustmentBorn_readjust_downgradedToExit() {
        EvaluationResult out = MonitorEvaluationService.applyReadjustmentExitGuard(
                result(MonitorAction.READJUST), true, 3);
        assertThat(out.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(out.reason()).contains("READJUST → EXIT").contains("readjustment re-entry");
        // Numbers + detail preserved
        assertThat(out.markToMarketPnl()).isEqualByComparingTo("-4200.00");
        assertThat(out.currentNetPremium()).isEqualByComparingTo("12.50");
        assertThat(out.livePop()).isEqualByComparingTo("0.63");
        assertThat(out.detail()).containsKey("spot");
        assertThat(out.thresholdHit()).isEqualTo(ThresholdHit.POP_READJUST);
    }

    @Test
    @DisplayName("Expiry day (DTE=0): READJUST → EXIT even for a normal trade")
    void expiryDay_readjust_downgradedToExit() {
        EvaluationResult out = MonitorEvaluationService.applyReadjustmentExitGuard(
                result(MonitorAction.READJUST), false, 0);
        assertThat(out.action()).isEqualTo(MonitorAction.EXIT);
        assertThat(out.reason()).contains("expiry day");
    }

    @Test
    @DisplayName("Normal trade, DTE>0: READJUST passes through unchanged")
    void normalTrade_readjust_unchanged() {
        EvaluationResult in = result(MonitorAction.READJUST);
        EvaluationResult out = MonitorEvaluationService.applyReadjustmentExitGuard(in, false, 3);
        assertThat(out.action()).isEqualTo(MonitorAction.READJUST);
        assertThat(out).isSameAs(in);
    }

    @Test
    @DisplayName("Readjustment-born trade: non-READJUST actions are untouched")
    void readjustmentBorn_nonReadjust_unchanged() {
        for (MonitorAction a : new MonitorAction[]{
                MonitorAction.HOLD, MonitorAction.WATCH, MonitorAction.EXIT, MonitorAction.PAUSE}) {
            EvaluationResult in = result(a);
            EvaluationResult out = MonitorEvaluationService.applyReadjustmentExitGuard(in, true, 3);
            assertThat(out.action()).as("action %s unchanged", a).isEqualTo(a);
            assertThat(out).as("same instance for %s", a).isSameAs(in);
        }
    }

    @Test
    @DisplayName("Expiry day: an existing EXIT is not double-wrapped")
    void expiryDay_exit_unchanged() {
        EvaluationResult in = result(MonitorAction.EXIT);
        EvaluationResult out = MonitorEvaluationService.applyReadjustmentExitGuard(in, false, 0);
        assertThat(out).isSameAs(in);
        assertThat(out.reason()).doesNotContain("READJUST → EXIT");
    }

    @Test
    @DisplayName("Null result is handled defensively")
    void nullResult_returnsNull() {
        assertThat(MonitorEvaluationService.applyReadjustmentExitGuard(null, true, 0)).isNull();
    }
}
