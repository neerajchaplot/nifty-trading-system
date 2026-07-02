package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ExpiryPnlService.computeExpiryPnl() and intrinsicValue().
 *
 * All tests are pure calculations — no Spring context, no DB, no Upstox API calls.
 * Only PnlCalculationService is real; all other dependencies are mocked.
 *
 * P&L formula at expiry (options settled at intrinsic value):
 *   Credit spread:  P&L = (actualNetPremium - (shortIntrinsic - longIntrinsic)) × positionSize
 *   Debit  spread:  P&L = (longIntrinsic - shortIntrinsic - actualNetPremium)   × positionSize
 *   Iron Condor:    P&L = (actualNetPremium - totalCloseCost) × positionSize
 *     where closeCost = (PE_short - PE_long) + (CE_short - CE_long) intrinsics
 */
@ExtendWith(MockitoExtension.class)
class ExpiryPnlServiceTest {

    @Mock private TradeMonitorReader        tradeReader;
    @Mock private UpstoxHistoricalDataClient historicalClient;
    @Mock private TradeLedgerService        ledger;
    @Mock private AlertService              alertService;
    @Mock private JdbcTemplate             jdbc;
    @Mock private JsonUtil                  jsonUtil;

    private ExpiryPnlService service;

    private static final int    LOTS     = 10;
    private static final int    LOT_SIZE = 65;
    private static final int    POSITION = LOTS * LOT_SIZE;   // 650 units

    @BeforeEach
    void setUp() {
        service = new ExpiryPnlService(
                tradeReader, historicalClient,
                new PnlCalculationService(),   // real — no deps
                ledger, alertService, jdbc, jsonUtil,
                new MonitoringProperties());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // intrinsicValue() — simple building-block tests
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void intrinsicValue_PE_OTM_returnsZero() {
        TradeLegDto leg = peLeg(23750, LegAction.SELL);
        assertThat(service.intrinsicValue(leg, new BigDecimal("24000")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void intrinsicValue_PE_ITM_returnsPositive() {
        TradeLegDto leg = peLeg(23750, LegAction.SELL);
        assertThat(service.intrinsicValue(leg, new BigDecimal("23700")))
                .isEqualByComparingTo(new BigDecimal("50"));
    }

    @Test
    void intrinsicValue_CE_OTM_returnsZero() {
        TradeLegDto leg = ceLeg(24000, LegAction.SELL);
        assertThat(service.intrinsicValue(leg, new BigDecimal("23900")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void intrinsicValue_CE_ITM_returnsPositive() {
        TradeLegDto leg = ceLeg(24000, LegAction.SELL);
        assertThat(service.intrinsicValue(leg, new BigDecimal("24050")))
                .isEqualByComparingTo(new BigDecimal("50"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BullPutSpread (CREDIT) — short PE 23750, long PE 23650, net credit 25.00
    // positionSize = 10 lots × 65 = 650
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void bullPutSpread_bothOTM_fullProfitRetained() {
        // S=24000 → both puts OTM → both expire worthless
        // P&L = (25 - 0) × 650 = +16,250
        MonitorConfigDto config = bullPutSpreadConfig("25.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("24000")))
                .isEqualByComparingTo(new BigDecimal("16250.00"));
    }

    @Test
    void bullPutSpread_shortLegITM_longOTM_partialLoss() {
        // S=23700 → short PE 23750 ITM by 50, long PE 23650 OTM
        // closeCost = (50 - 0) = 50
        // P&L = (25 - 50) × 650 = -16,250
        MonitorConfigDto config = bullPutSpreadConfig("25.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("23700")))
                .isEqualByComparingTo(new BigDecimal("-16250.00"));
    }

    @Test
    void bullPutSpread_bothITM_maxLoss() {
        // S=23500 → short PE 23750 ITM by 250, long PE 23650 ITM by 150
        // closeCost = (250 - 150) = 100
        // P&L = (25 - 100) × 650 = -48,750
        MonitorConfigDto config = bullPutSpreadConfig("25.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("23500")))
                .isEqualByComparingTo(new BigDecimal("-48750.00"));
    }

    @Test
    void bullPutSpread_exactShortStrike_shortLegAtZero() {
        // S=23750 exactly → short PE intrinsic = 0, long PE intrinsic = 0
        // P&L = (25 - 0) × 650 = +16,250 (expires exactly at strike, no intrinsic)
        MonitorConfigDto config = bullPutSpreadConfig("25.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("23750")))
                .isEqualByComparingTo(new BigDecimal("16250.00"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BearCallSpread (CREDIT) — short CE 24000, long CE 24100, net credit 20.00
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void bearCallSpread_bothOTM_fullProfitRetained() {
        // S=23900 → both calls OTM → both expire worthless
        // P&L = (20 - 0) × 650 = +13,000
        MonitorConfigDto config = bearCallSpreadConfig("20.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("23900")))
                .isEqualByComparingTo(new BigDecimal("13000.00"));
    }

    @Test
    void bearCallSpread_shortLegITM_longOTM_partialLoss() {
        // S=24050 → short CE 24000 ITM by 50, long CE 24100 OTM
        // P&L = (20 - 50) × 650 = -19,500
        MonitorConfigDto config = bearCallSpreadConfig("20.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("24050")))
                .isEqualByComparingTo(new BigDecimal("-19500.00"));
    }

    @Test
    void bearCallSpread_bothITM_maxLoss() {
        // S=24200 → short CE 24000 ITM by 200, long CE 24100 ITM by 100
        // closeCost = 200 - 100 = 100
        // P&L = (20 - 100) × 650 = -52,000
        MonitorConfigDto config = bearCallSpreadConfig("20.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("24200")))
                .isEqualByComparingTo(new BigDecimal("-52000.00"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BullCallSpread (DEBIT) — long CE 23800, short CE 24000, net debit 25.00
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void bullCallSpread_debit_bothOTM_fullLoss() {
        // S=23700 → long CE 23800 OTM, short CE 24000 OTM → both expire worthless
        // P&L = (0 - 0 - 25) × 650 = -16,250
        MonitorConfigDto config = bullCallSpreadConfig("25.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("23700")))
                .isEqualByComparingTo(new BigDecimal("-16250.00"));
    }

    @Test
    void bullCallSpread_debit_longITM_shortOTM_partialProfit() {
        // S=23900 → long CE 23800 ITM by 100, short CE 24000 OTM
        // P&L = (100 - 0 - 25) × 650 = +48,750
        MonitorConfigDto config = bullCallSpreadConfig("25.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("23900")))
                .isEqualByComparingTo(new BigDecimal("48750.00"));
    }

    @Test
    void bullCallSpread_debit_bothITM_maxProfit() {
        // S=24100 → long CE 23800 ITM by 300, short CE 24000 ITM by 100
        // P&L = (300 - 100 - 25) × 650 = +113,750
        MonitorConfigDto config = bullCallSpreadConfig("25.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("24100")))
                .isEqualByComparingTo(new BigDecimal("113750.00"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // IronCondor (CREDIT) — PE short 23600, PE long 23500, CE short 24000, CE long 24100
    // net credit 30.00, 650 units
    // closeCost = (PE_short_intrinsic - PE_long_intrinsic) + (CE_short_intrinsic - CE_long_intrinsic)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void ironCondor_allOTM_sweetSpot_fullProfitRetained() {
        // S=23800 — between both short strikes → all legs expire worthless
        // P&L = (30 - 0) × 650 = +19,500
        MonitorConfigDto config = ironCondorConfig("30.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("23800")))
                .isEqualByComparingTo(new BigDecimal("19500.00"));
    }

    @Test
    void ironCondor_peShortBreached_partialLoss() {
        // S=23550 → PE short (23600) ITM by 50; PE long (23500) OTM; both CEs OTM
        // closeCost = (50 - 0) + (0 - 0) = 50
        // P&L = (30 - 50) × 650 = -13,000
        MonitorConfigDto config = ironCondorConfig("30.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("23550")))
                .isEqualByComparingTo(new BigDecimal("-13000.00"));
    }

    @Test
    void ironCondor_ceShortBreached_partialLoss() {
        // S=24050 → CE short (24000) ITM by 50; CE long (24100) OTM; both PEs OTM
        // closeCost = 0 + (50 - 0) = 50
        // P&L = (30 - 50) × 650 = -13,000
        MonitorConfigDto config = ironCondorConfig("30.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("24050")))
                .isEqualByComparingTo(new BigDecimal("-13000.00"));
    }

    @Test
    void ironCondor_bothPELegsITM_maxLossOnPutSide() {
        // S=23400 → PE short (23600) ITM by 200; PE long (23500) ITM by 100; both CEs OTM
        // closeCost = (200 - 100) + 0 = 100
        // P&L = (30 - 100) × 650 = -45,500
        MonitorConfigDto config = ironCondorConfig("30.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("23400")))
                .isEqualByComparingTo(new BigDecimal("-45500.00"));
    }

    @Test
    void ironCondor_bothCELegsITM_maxLossOnCallSide() {
        // S=24200 → both PEs OTM; CE short (24000) ITM by 200; CE long (24100) ITM by 100
        // closeCost = 0 + (200 - 100) = 100
        // P&L = (30 - 100) × 650 = -45,500
        MonitorConfigDto config = ironCondorConfig("30.00");
        assertThat(service.computeExpiryPnl(config, new BigDecimal("24200")))
                .isEqualByComparingTo(new BigDecimal("-45500.00"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private MonitorConfigDto bullPutSpreadConfig(String netCredit) {
        TradeLegDto shortLeg = peLeg(23750, LegAction.SELL);
        TradeLegDto longLeg  = peLeg(23650, LegAction.BUY);
        return MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT,
                shortLeg, longLeg, new BigDecimal(netCredit), LOTS, LOT_SIZE,
                new BigDecimal("16250"), new BigDecimal("48750"),
                false, null,
                MonitorThresholdsDto.twoLeg(null, null, null, null, null),
                LocalDate.now().minusDays(1), 0);
    }

    private MonitorConfigDto bearCallSpreadConfig(String netCredit) {
        TradeLegDto shortLeg = ceLeg(24000, LegAction.SELL);
        TradeLegDto longLeg  = ceLeg(24100, LegAction.BUY);
        return MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BEAR_CALL_SPREAD, SpreadDirection.CREDIT,
                shortLeg, longLeg, new BigDecimal(netCredit), LOTS, LOT_SIZE,
                new BigDecimal("13000"), new BigDecimal("52000"),
                false, null,
                MonitorThresholdsDto.twoLeg(null, null, null, null, null),
                LocalDate.now().minusDays(1), 0);
    }

    private MonitorConfigDto bullCallSpreadConfig(String netDebit) {
        // shortLeg = higher-strike CE SELL; longLeg = lower-strike CE BUY
        TradeLegDto shortLeg = ceLeg(24000, LegAction.SELL);
        TradeLegDto longLeg  = ceLeg(23800, LegAction.BUY);
        return MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BULL_CALL_SPREAD, SpreadDirection.DEBIT,
                shortLeg, longLeg, new BigDecimal(netDebit), LOTS, LOT_SIZE,
                new BigDecimal("113750"), new BigDecimal("16250"),
                false, null,
                MonitorThresholdsDto.twoLeg(null, null, null, null, null),
                LocalDate.now().minusDays(1), 0);
    }

    private MonitorConfigDto ironCondorConfig(String netCredit) {
        TradeLegDto peShort = peLeg(23600, LegAction.SELL);
        TradeLegDto peLong  = peLeg(23500, LegAction.BUY);
        TradeLegDto ceShort = ceLeg(24000, LegAction.SELL);
        TradeLegDto ceLong  = ceLeg(24100, LegAction.BUY);
        return MonitorConfigDto.ironCondor(
                UUID.randomUUID(), Strategy.IRON_CONDOR, SpreadDirection.CREDIT,
                peShort, peLong, ceShort, ceLong,
                new BigDecimal(netCredit), LOTS, LOT_SIZE,
                new BigDecimal("19500"), new BigDecimal("45500"),
                false, null,
                MonitorThresholdsDto.ironCondor(null, null, null, null, null, null, null, null),
                LocalDate.now().minusDays(1), 0);
    }

    private TradeLegDto peLeg(int strike, LegAction action) {
        return new TradeLegDto(OptionType.PE, strike, BigDecimal.ZERO,
                action, BigDecimal.ZERO, BigDecimal.ZERO,
                "NSE_FO|NIFTY" + strike + "PE");
    }

    private TradeLegDto ceLeg(int strike, LegAction action) {
        return new TradeLegDto(OptionType.CE, strike, BigDecimal.ZERO,
                action, BigDecimal.ZERO, BigDecimal.ZERO,
                "NSE_FO|NIFTY" + strike + "CE");
    }
}
