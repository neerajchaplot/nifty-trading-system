package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PnlCalculationServiceTest {

    private final PnlCalculationService service = new PnlCalculationService();

    // Worked example from CLAUDE.md:
    // BULL_PUT_SPREAD: short PE 23750, long PE 23650, net credit received = 23.20
    // lots=54, lotSize=65 → total position = 3510 units
    // If current net premium = 35.00 (spread widened — loss scenario)
    // P&L = (23.20 - 35.00) × 3510 = -41,418

    @Test
    void calculateMtmPnl_creditSpread_spreadWidened_returnsLoss() {
        MonitorConfigDto config = buildCreditConfig(23.20, 54, 65);
        BigDecimal pnl = service.calculateMtmPnl(config,
                new BigDecimal("55.00"),   // short leg LTP (up from 68.40)
                new BigDecimal("20.00"));  // long leg LTP (current net = 35.00)
        // currentNetPremium = 55 - 20 = 35
        // P&L = (23.20 - 35.00) × 3510 = -41418
        assertThat(pnl).isEqualByComparingTo(new BigDecimal("-41418.00"));
    }

    @Test
    void calculateMtmPnl_creditSpread_spreadNarrowed_returnsProfit() {
        MonitorConfigDto config = buildCreditConfig(23.20, 54, 65);
        BigDecimal pnl = service.calculateMtmPnl(config,
                new BigDecimal("15.00"),   // short leg has decayed
                new BigDecimal("5.00"));   // long leg has decayed
        // currentNetPremium = 10.00 (spread narrowed — good for credit spread)
        // P&L = (23.20 - 10.00) × 3510 = +46332
        assertThat(pnl).isEqualByComparingTo(new BigDecimal("46332.00"));
    }

    @Test
    void calculateMtmPnl_debitSpread_spreadWidened_returnsProfit() {
        // Bull Call Spread: paid net debit of 25.00, now worth 35.00
        MonitorConfigDto config = buildDebitConfig(25.00, 10, 65);
        BigDecimal pnl = service.calculateMtmPnl(config,
                new BigDecimal("15.00"),   // short leg LTP
                new BigDecimal("50.00"));  // long leg LTP (long call gained)
        // currentNetPremium = longLeg - shortLeg = 50 - 15 = 35
        // P&L = (35 - 25) × 650 = +6500
        assertThat(pnl).isEqualByComparingTo(new BigDecimal("6500.00"));
    }

    @Test
    void hasBreachedLossThreshold_exactThreshold_returnsTrue() {
        assertThat(service.hasBreachedLossThreshold(
                new BigDecimal("-67138.00"), new BigDecimal("67138.00"))).isTrue();
    }

    @Test
    void hasBreachedLossThreshold_belowThreshold_returnsFalse() {
        assertThat(service.hasBreachedLossThreshold(
                new BigDecimal("-10000.00"), new BigDecimal("67138.00"))).isFalse();
    }

    @Test
    void hasBreachedLossThreshold_profit_returnsFalse() {
        assertThat(service.hasBreachedLossThreshold(
                new BigDecimal("5000.00"), new BigDecimal("67138.00"))).isFalse();
    }

    @Test
    void hasBreachedLossThreshold_nullThreshold_returnsFalse() {
        assertThat(service.hasBreachedLossThreshold(
                new BigDecimal("-100000.00"), null)).isFalse();
    }

    private MonitorConfigDto buildCreditConfig(double netPremium, int lots, int lotSize) {
        TradeLegDto shortLeg = new TradeLegDto(OptionType.PE, 23750, new BigDecimal("68.40"),
                LegAction.SELL, new BigDecimal("-0.169"), new BigDecimal("0.826"), null);
        TradeLegDto longLeg = new TradeLegDto(OptionType.PE, 23650, new BigDecimal("45.20"),
                LegAction.BUY, new BigDecimal("-0.142"), new BigDecimal("0.858"), null);
        MonitorThresholdsDto thr = MonitorThresholdsDto.twoLeg(
                new BigDecimal("23900"), new BigDecimal("23825"),
                new BigDecimal("23750"), new BigDecimal("67138.00"), new BigDecimal("134277.00"));
        return MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT,
                shortLeg, longLeg, new BigDecimal(netPremium), lots, lotSize,
                new BigDecimal("81396"), new BigDecimal("268554"), false, null,
                thr, LocalDate.now().plusDays(5), 5);
    }

    private MonitorConfigDto buildDebitConfig(double netPremium, int lots, int lotSize) {
        TradeLegDto shortLeg = new TradeLegDto(OptionType.CE, 24000, new BigDecimal("20.00"),
                LegAction.SELL, new BigDecimal("0.35"), new BigDecimal("0.65"), null);
        TradeLegDto longLeg = new TradeLegDto(OptionType.CE, 23800, new BigDecimal("45.00"),
                LegAction.BUY, new BigDecimal("0.55"), new BigDecimal("0.45"), null);
        MonitorThresholdsDto thr = MonitorThresholdsDto.twoLeg(
                new BigDecimal("24100"), new BigDecimal("24200"),
                null, new BigDecimal("8125.00"), null);
        return MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT,
                shortLeg, longLeg, new BigDecimal(netPremium), lots, lotSize,
                new BigDecimal("13000"), new BigDecimal("16250"), false, null,
                thr, LocalDate.now().plusDays(5), 5);
    }
}
