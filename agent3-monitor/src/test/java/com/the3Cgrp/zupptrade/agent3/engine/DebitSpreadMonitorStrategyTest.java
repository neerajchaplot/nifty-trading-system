package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.engine.DebitSpreadMonitorStrategy.DebitAction;
import com.the3Cgrp.zupptrade.agent3.math.BlackScholesCalculator;
import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.agent3.model.MonitorEvaluationContext;
import com.the3Cgrp.zupptrade.agent3.service.PnlCalculationService;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure debit decision {@link DebitSpreadMonitorStrategy#decideDebit}.
 *
 * Direction-agnostic (bull call and bear put both feed it directional PoP/PoPP):
 *   Rule 1 (disaster stop):  livePoP ≤ entryPoP × (1 − 0.20)                       → EXIT_DISASTER
 *   Rule 2 (give-back lock): (livePoP − livePoPP) − (entryPoP − entryPoPP) ≥ 0.10  → EXIT_GIVEBACK
 *   else                                                                          → HOLD
 *
 * PoP values are fractions (0–1). Entry PoP 45.1%, PoPP 33.3% (gap 11.8pp) mirrors the worked example.
 */
class DebitSpreadMonitorStrategyTest {

    private static final BigDecimal DROP = new BigDecimal("0.20");   // 20% of entry PoP
    private static final BigDecimal GAP  = new BigDecimal("0.10");   // 10 percentage points

    private static DebitAction decide(String entryPop, String entryPopp, String livePop, String livePopp) {
        return DebitSpreadMonitorStrategy.decideDebit(
                new BigDecimal(entryPop), new BigDecimal(entryPopp),
                new BigDecimal(livePop), new BigDecimal(livePopp), DROP, GAP);
    }

    // ── Rule 1 — disaster stop ────────────────────────────────────────────────────

    @Test
    @DisplayName("Rule 1: PoP falls to 20% below entry → EXIT_DISASTER")
    void disasterStop_fires() {
        // entry 45.1% → floor = 45.1% × 0.80 = 36.08%. livePoP 33.9% ≤ floor → exit.
        assertThat(decide("0.451", "0.333", "0.339", "0.250")).isEqualTo(DebitAction.EXIT_DISASTER);
    }

    @Test
    @DisplayName("Rule 1: PoP just above the 20% floor → not disaster")
    void disasterStop_justAboveFloor_holds() {
        // floor = 36.08%; livePoP 36.5% > floor, gap not widened → HOLD
        assertThat(decide("0.451", "0.333", "0.365", "0.300")).isEqualTo(DebitAction.HOLD);
    }

    @Test
    @DisplayName("Rule 1: exactly at the floor → EXIT_DISASTER (≤)")
    void disasterStop_atFloor_exits() {
        assertThat(decide("0.500", "0.380", "0.400", "0.300")).isEqualTo(DebitAction.EXIT_DISASTER); // floor=0.40
    }

    // ── Rule 2 — give-back lock ───────────────────────────────────────────────────

    @Test
    @DisplayName("Rule 2: gap widens +10pp above entry gap → EXIT_GIVEBACK (retrace-from-peak)")
    void givebackLock_fires() {
        // entry gap 11.8pp. Retrace: PoP 76%, PoPP 51% → gap 25pp = +13.2pp ≥ +10 → exit.
        assertThat(decide("0.451", "0.333", "0.760", "0.510")).isEqualTo(DebitAction.EXIT_GIVEBACK);
    }

    @Test
    @DisplayName("Rule 2: gap widened only +8pp → HOLD (the exact PoP71/PoPP51 point, just under)")
    void givebackLock_justUnder_holds() {
        // entry gap 11.8pp; live gap 71−51 = 20pp = +8.2pp < +10 → HOLD (matches the worked example)
        assertThat(decide("0.451", "0.333", "0.710", "0.510")).isEqualTo(DebitAction.HOLD);
    }

    @Test
    @DisplayName("Disaster takes priority over give-back when both could trigger")
    void disasterBeforeGiveback() {
        // PoP 30% (<36% floor) AND gap wide — disaster wins.
        assertThat(decide("0.451", "0.333", "0.300", "0.050")).isEqualTo(DebitAction.EXIT_DISASTER);
    }

    // ── Rule 3 — hold (ride to max / converging) ──────────────────────────────────

    @Test
    @DisplayName("Rule 3: PoP high, gap narrowing (converging to max profit) → HOLD")
    void hold_converging() {
        // deep ITM: PoP 89%, PoPP 71% → gap 18pp = +6.2pp < +10, PoP well above floor → HOLD
        assertThat(decide("0.451", "0.333", "0.890", "0.710")).isEqualTo(DebitAction.HOLD);
    }

    @Test
    @DisplayName("Rule 3: mild climb still inside thresholds → HOLD")
    void hold_mildClimb() {
        assertThat(decide("0.451", "0.333", "0.547", "0.411")).isEqualTo(DebitAction.HOLD); // gap 13.6pp = +1.8
    }

    // ── Bear put mirror — same rule, direction handled upstream by the PoP definition ──

    @Test
    @DisplayName("Bear put: identical rule (directional PoP already encodes 'profit as spot falls')")
    void bearPut_sameLogic() {
        // entry PoP 48%, PoPP 34% (gap 14pp). Disaster floor = 38.4%. livePoP 37% → EXIT_DISASTER.
        assertThat(decide("0.480", "0.340", "0.370", "0.280")).isEqualTo(DebitAction.EXIT_DISASTER);
        // Give-back on retrace: gap 78−52 = 26pp vs entry 14pp = +12pp → EXIT_GIVEBACK.
        assertThat(decide("0.480", "0.340", "0.780", "0.520")).isEqualTo(DebitAction.EXIT_GIVEBACK);
        // Healthy climb: gap +3pp, PoP above floor → HOLD.
        assertThat(decide("0.480", "0.340", "0.600", "0.430")).isEqualTo(DebitAction.HOLD);
    }

    // ── Degenerate inputs ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing PoP → HOLD (caller falls back to WATCH)")
    void nullInputs_hold() {
        assertThat(DebitSpreadMonitorStrategy.decideDebit(null, null, null, null, DROP, GAP))
                .isEqualTo(DebitAction.HOLD);
    }

    @Test
    @DisplayName("Missing PoPP → give-back skipped, disaster still evaluated")
    void nullPopp_disasterStillWorks() {
        assertThat(DebitSpreadMonitorStrategy.decideDebit(
                new BigDecimal("0.451"), null, new BigDecimal("0.300"), null, DROP, GAP))
                .isEqualTo(DebitAction.EXIT_DISASTER);
        assertThat(DebitSpreadMonitorStrategy.decideDebit(
                new BigDecimal("0.451"), null, new BigDecimal("0.440"), null, DROP, GAP))
                .isEqualTo(DebitAction.HOLD);
    }

    // ── Observability: the detail must carry shortLegIv (the input that drives the loss-cut) ──

    @Test
    @DisplayName("evaluate() records shortLegIv in the detail so the loss-cut driver is auditable")
    void evaluate_recordsShortLegIvInDetail() {
        DebitSpreadMonitorStrategy strategy = new DebitSpreadMonitorStrategy(
                new PnlCalculationService(), new BlackScholesCalculator(), new MonitoringProperties());

        // Bear put: BUY 24450 PE, SELL 24250 PE. netDebit=74.30, breakeven=24375.70.
        TradeLegDto shortLeg = new TradeLegDto(OptionType.PE, 24250, new BigDecimal("50.10"),
                LegAction.SELL, new BigDecimal("-0.35"), new BigDecimal("0.35"), "PE_SHORT");
        TradeLegDto longLeg = new TradeLegDto(OptionType.PE, 24450, new BigDecimal("124.40"),
                LegAction.BUY, new BigDecimal("-0.55"), new BigDecimal("0.55"), "PE_LONG");
        MonitorThresholdsDto thr = MonitorThresholdsDto.debitSpread(
                new BigDecimal("24375.70"), new BigDecimal("24250"), new BigDecimal("24450"),
                new BigDecimal("2400"), new BigDecimal("4830"),
                new BigDecimal("0.396"), new BigDecimal("0.256"));
        MonitorConfigDto config = MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BEAR_PUT_SPREAD, SpreadDirection.DEBIT,
                shortLeg, longLeg, new BigDecimal("74.30"), 1, 65,
                new BigDecimal("8255"), new BigDecimal("4830"), false, null,
                thr, LocalDate.now().plusDays(5), 5);

        BigDecimal iv = new BigDecimal("0.10");
        LiveMarketSnapshot live = new LiveMarketSnapshot(
                new BigDecimal("24343.90"), new BigDecimal("12.0"),
                new BigDecimal("50.10"), new BigDecimal("124.40"), iv);
        MonitorEvaluationContext ctx = new MonitorEvaluationContext(config, live, 5, null, null);

        EvaluationResult result = strategy.evaluate(ctx);

        // IV was present (else action would be WATCH) AND it is now persisted in the detail for audit.
        assertThat(result.detail()).containsKey("shortLegIv");
        assertThat((BigDecimal) result.detail().get("shortLegIv")).isEqualByComparingTo(iv);
        assertThat(result.detail()).containsKey("liveLossCutLevel");   // loss-cut computed this cycle
    }
}
