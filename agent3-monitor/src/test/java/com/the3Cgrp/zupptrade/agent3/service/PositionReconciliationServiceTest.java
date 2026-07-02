package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.ledger.LedgerEventType;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PositionReconciliationService.reconcile().
 *
 * Each test drives the service with a specific positions map and a pre-stubbed
 * MonitorConfigDto (via mocked JsonUtil). Verifications cover:
 *   - Which trade IDs are returned as externally closed / corrupted
 *   - Which DB update SQL was called
 *   - Which ledger event was recorded
 *   - Whether info vs critical alerts were fired
 */
@ExtendWith(MockitoExtension.class)
class PositionReconciliationServiceTest {

    @Mock private JdbcTemplate       jdbc;
    @Mock private JsonUtil           jsonUtil;
    @Mock private AlertService       alertService;
    @Mock private TradeLedgerService ledger;

    private PositionReconciliationService service;

    // Instrument key constants used across tests
    private static final String KEY_SHORT = "NSE_FO|NIFTY23750PE";
    private static final String KEY_LONG  = "NSE_FO|NIFTY23650PE";
    private static final String KEY_PE_SHORT = "NSE_FO|NIFTY23600PE";
    private static final String KEY_PE_LONG  = "NSE_FO|NIFTY23500PE";
    private static final String KEY_CE_SHORT = "NSE_FO|NIFTY24000CE";
    private static final String KEY_CE_LONG  = "NSE_FO|NIFTY24100CE";

    @BeforeEach
    void setUp() {
        service = new PositionReconciliationService(jdbc, jsonUtil, alertService, ledger);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard: empty positions map → skip entire cycle
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_emptyPositions_noActionTaken() {
        TradeMonitorData trade = activeTrade("cfg", UUID.randomUUID());
        Set<UUID> result = service.reconcile(List.of(trade), Map.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbc, ledger, alertService, jsonUtil);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard: null monitorConfigJson → skip that trade
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_nullMonitorConfig_tradeSkipped() {
        UUID tradeId = UUID.randomUUID();
        TradeMonitorData trade = new TradeMonitorData(
                tradeId, null, TradeStatus.ACTIVE,
                null,  // monitorConfigJson is null
                null, "TEST001", LocalDate.now(), null);

        Map<String, Integer> positions = Map.of(KEY_SHORT, 0, KEY_LONG, 0);
        Set<UUID> result = service.reconcile(List.of(trade), positions);

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbc, ledger, alertService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2-leg spread: both legs flat → EXTERNALLY_CLOSED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_twoLeg_bothFlat_markedExternallyClosed() {
        UUID tradeId = UUID.randomUUID();
        TradeMonitorData trade = activeTrade("cfg-2leg", tradeId);
        stubTwoLegConfig("cfg-2leg");

        // Both legs show qty = 0 on Upstox
        Map<String, Integer> positions = Map.of(KEY_SHORT, 0, KEY_LONG, 0);
        Set<UUID> result = service.reconcile(List.of(trade), positions);

        assertThat(result).containsExactly(tradeId);

        // DB: status → CLOSED with close_reason EXTERNALLY_CLOSED
        ArgumentCaptor<Object[]> sqlArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("EXTERNALLY_CLOSED"), eq(tradeId));

        // Ledger: TRADE_EXTERNALLY_CLOSED event
        verify(ledger).record(eq(tradeId), eq(LedgerEventType.TRADE_EXTERNALLY_CLOSED), any(), any());

        // Alert: informational — not critical
        verify(alertService).info(eq(tradeId), eq("external_close"), any());
        verify(alertService, never()).critical(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2-leg spread: short leg flat, long leg open → CORRUPTED_MANUALLY
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_twoLeg_shortFlatLongOpen_markedCorrupted() {
        UUID tradeId = UUID.randomUUID();
        TradeMonitorData trade = activeTrade("cfg-2leg", tradeId);
        stubTwoLegConfig("cfg-2leg");

        Map<String, Integer> positions = Map.of(KEY_SHORT, 0, KEY_LONG, 65);
        Set<UUID> result = service.reconcile(List.of(trade), positions);

        assertThat(result).containsExactly(tradeId);

        verify(jdbc).update(contains("CORRUPTED_MANUALLY"), any(), eq(tradeId));
        verify(ledger).record(eq(tradeId), eq(LedgerEventType.TRADE_CORRUPTED_MANUALLY), any(), any());
        verify(alertService).critical(eq(tradeId), eq("partial_close_corrupted"), any());
        verify(alertService, never()).info(eq(tradeId), eq("external_close"), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2-leg spread: long leg flat, short leg open → CORRUPTED_MANUALLY
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_twoLeg_longFlatShortOpen_markedCorrupted() {
        UUID tradeId = UUID.randomUUID();
        TradeMonitorData trade = activeTrade("cfg-2leg", tradeId);
        stubTwoLegConfig("cfg-2leg");

        Map<String, Integer> positions = Map.of(KEY_SHORT, 65, KEY_LONG, 0);
        Set<UUID> result = service.reconcile(List.of(trade), positions);

        assertThat(result).containsExactly(tradeId);
        verify(jdbc).update(contains("CORRUPTED_MANUALLY"), any(), eq(tradeId));
        verify(alertService).critical(eq(tradeId), eq("partial_close_corrupted"), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2-leg spread: both legs still open → no action
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_twoLeg_bothOpen_noAction() {
        UUID tradeId = UUID.randomUUID();
        TradeMonitorData trade = activeTrade("cfg-2leg", tradeId);
        stubTwoLegConfig("cfg-2leg");

        Map<String, Integer> positions = Map.of(KEY_SHORT, 65, KEY_LONG, 65);
        Set<UUID> result = service.reconcile(List.of(trade), positions);

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbc, ledger, alertService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2-leg spread: a leg key is absent from positions map → skip (insufficient data)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_twoLeg_legKeyMissing_tradeSkipped() {
        UUID tradeId = UUID.randomUUID();
        TradeMonitorData trade = activeTrade("cfg-2leg", tradeId);
        stubTwoLegConfig("cfg-2leg");

        // Only one key present — the other is absent (Upstox API partial response)
        Map<String, Integer> positions = Map.of(KEY_SHORT, 0);
        Set<UUID> result = service.reconcile(List.of(trade), positions);

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbc, ledger, alertService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Iron Condor: all 4 legs flat → EXTERNALLY_CLOSED
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_ironCondor_allFlat_markedExternallyClosed() {
        UUID tradeId = UUID.randomUUID();
        TradeMonitorData trade = activeTrade("cfg-ic", tradeId);
        stubIronCondorConfig("cfg-ic");

        Map<String, Integer> positions = Map.of(
                KEY_PE_SHORT, 0, KEY_PE_LONG, 0,
                KEY_CE_SHORT, 0, KEY_CE_LONG, 0);
        Set<UUID> result = service.reconcile(List.of(trade), positions);

        assertThat(result).containsExactly(tradeId);
        verify(jdbc).update(contains("EXTERNALLY_CLOSED"), eq(tradeId));
        verify(ledger).record(eq(tradeId), eq(LedgerEventType.TRADE_EXTERNALLY_CLOSED), any(), any());
        verify(alertService).info(eq(tradeId), eq("external_close"), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Iron Condor: 3 of 4 legs flat, 1 still open → CORRUPTED_MANUALLY
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_ironCondor_threeLegsFlat_oneOpen_markedCorrupted() {
        UUID tradeId = UUID.randomUUID();
        TradeMonitorData trade = activeTrade("cfg-ic", tradeId);
        stubIronCondorConfig("cfg-ic");

        // CE long still open — probably user forgot to close it
        Map<String, Integer> positions = Map.of(
                KEY_PE_SHORT, 0, KEY_PE_LONG, 0,
                KEY_CE_SHORT, 0, KEY_CE_LONG, 65);
        Set<UUID> result = service.reconcile(List.of(trade), positions);

        assertThat(result).containsExactly(tradeId);
        verify(jdbc).update(contains("CORRUPTED_MANUALLY"), any(), eq(tradeId));
        verify(ledger).record(eq(tradeId), eq(LedgerEventType.TRADE_CORRUPTED_MANUALLY), any(), any());
        verify(alertService).critical(eq(tradeId), eq("partial_close_corrupted"), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Iron Condor: one leg key missing from positions map → skip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_ironCondor_legKeyMissing_tradeSkipped() {
        UUID tradeId = UUID.randomUUID();
        TradeMonitorData trade = activeTrade("cfg-ic", tradeId);
        stubIronCondorConfig("cfg-ic");

        // CE long absent from Upstox response
        Map<String, Integer> positions = Map.of(
                KEY_PE_SHORT, 0, KEY_PE_LONG, 0, KEY_CE_SHORT, 0);
        Set<UUID> result = service.reconcile(List.of(trade), positions);

        assertThat(result).isEmpty();
        verifyNoInteractions(jdbc, ledger, alertService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multiple trades in one cycle — each evaluated independently
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void reconcile_multipleTrades_eachEvaluatedIndependently() {
        UUID closedId   = UUID.randomUUID();
        UUID corruptId  = UUID.randomUUID();
        UUID normalId   = UUID.randomUUID();

        TradeMonitorData closed  = activeTrade("cfg-closed",  closedId);
        TradeMonitorData corrupt = activeTrade("cfg-corrupt", corruptId);
        TradeMonitorData normal  = activeTrade("cfg-normal",  normalId);

        // Each has different config but same 2-leg structure with different keys
        String ckShort  = "NSE_FO|NIFTY24000PE_c";
        String ckLong   = "NSE_FO|NIFTY23900PE_c";
        String rpShort  = "NSE_FO|NIFTY24000PE_r";
        String rpLong   = "NSE_FO|NIFTY23900PE_r";
        String nkShort  = "NSE_FO|NIFTY24000PE_n";
        String nkLong   = "NSE_FO|NIFTY23900PE_n";

        when(jsonUtil.fromJson(eq("cfg-closed"),  eq(MonitorConfigDto.class))).thenReturn(twoLegConfig(ckShort, ckLong));
        when(jsonUtil.fromJson(eq("cfg-corrupt"), eq(MonitorConfigDto.class))).thenReturn(twoLegConfig(rpShort, rpLong));
        when(jsonUtil.fromJson(eq("cfg-normal"),  eq(MonitorConfigDto.class))).thenReturn(twoLegConfig(nkShort, nkLong));

        Map<String, Integer> positions = Map.of(
                ckShort, 0,  ckLong, 0,   // closed trade — both flat
                rpShort, 0,  rpLong, 65,  // corrupted trade — one flat
                nkShort, 65, nkLong, 65); // normal trade — both open

        Set<UUID> result = service.reconcile(List.of(closed, corrupt, normal), positions);

        assertThat(result).containsExactlyInAnyOrder(closedId, corruptId);
        verify(alertService).info(eq(closedId),  eq("external_close"),           any());
        verify(alertService).critical(eq(corruptId), eq("partial_close_corrupted"), any());
        verify(alertService, never()).info(eq(normalId),   eq("external_close"), any());
        verify(alertService, never()).critical(eq(normalId), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private TradeMonitorData activeTrade(String configJson, UUID tradeId) {
        return new TradeMonitorData(
                tradeId, null, TradeStatus.ACTIVE,
                configJson, null, "ZUPP_" + tradeId.toString().substring(0, 8).toUpperCase(),
                LocalDate.now().plusDays(3), null);
    }

    private void stubTwoLegConfig(String configKey) {
        when(jsonUtil.fromJson(eq(configKey), eq(MonitorConfigDto.class)))
                .thenReturn(twoLegConfig(KEY_SHORT, KEY_LONG));
    }

    private void stubIronCondorConfig(String configKey) {
        when(jsonUtil.fromJson(eq(configKey), eq(MonitorConfigDto.class)))
                .thenReturn(ironCondorConfig(KEY_PE_SHORT, KEY_PE_LONG, KEY_CE_SHORT, KEY_CE_LONG));
    }

    private MonitorConfigDto twoLegConfig(String shortKey, String longKey) {
        TradeLegDto shortLeg = new TradeLegDto(OptionType.PE, 24000, BigDecimal.ZERO,
                LegAction.SELL, BigDecimal.ZERO, BigDecimal.ZERO, shortKey);
        TradeLegDto longLeg = new TradeLegDto(OptionType.PE, 23900, BigDecimal.ZERO,
                LegAction.BUY, BigDecimal.ZERO, BigDecimal.ZERO, longKey);
        return MonitorConfigDto.twoLeg(
                UUID.randomUUID(), Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT,
                shortLeg, longLeg, new BigDecimal("25.00"), 10, 65,
                new BigDecimal("16250"), new BigDecimal("48750"), false, null,
                MonitorThresholdsDto.twoLeg(null, null, null, null, null),
                LocalDate.now().plusDays(3), 3);
    }

    private MonitorConfigDto ironCondorConfig(String peShortKey, String peLongKey,
                                               String ceShortKey, String ceLongKey) {
        TradeLegDto peShort = new TradeLegDto(OptionType.PE, 23600, BigDecimal.ZERO,
                LegAction.SELL, BigDecimal.ZERO, BigDecimal.ZERO, peShortKey);
        TradeLegDto peLong  = new TradeLegDto(OptionType.PE, 23500, BigDecimal.ZERO,
                LegAction.BUY, BigDecimal.ZERO, BigDecimal.ZERO, peLongKey);
        TradeLegDto ceShort = new TradeLegDto(OptionType.CE, 24000, BigDecimal.ZERO,
                LegAction.SELL, BigDecimal.ZERO, BigDecimal.ZERO, ceShortKey);
        TradeLegDto ceLong  = new TradeLegDto(OptionType.CE, 24100, BigDecimal.ZERO,
                LegAction.BUY, BigDecimal.ZERO, BigDecimal.ZERO, ceLongKey);
        return MonitorConfigDto.ironCondor(
                UUID.randomUUID(), Strategy.IRON_CONDOR, SpreadDirection.CREDIT,
                peShort, peLong, ceShort, ceLong,
                new BigDecimal("30.00"), 10, 65,
                new BigDecimal("19500"), new BigDecimal("45500"), false, null,
                MonitorThresholdsDto.ironCondor(null, null, null, null, null, null, null, null),
                LocalDate.now().plusDays(3), 3);
    }
}
