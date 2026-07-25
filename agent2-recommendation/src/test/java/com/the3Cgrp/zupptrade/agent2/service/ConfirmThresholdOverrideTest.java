package com.the3Cgrp.zupptrade.agent2.service;

import tools.jackson.core.type.TypeReference;
import com.the3Cgrp.zupptrade.agent2.client.MarketDataClient;
import com.the3Cgrp.zupptrade.agent2.client.OptionChainClient;
import com.the3Cgrp.zupptrade.agent2.domain.entity.TradeEntity;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationEngine;
import com.the3Cgrp.zupptrade.agent2.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.agent2.repository.ReferenceDataRepository;
import com.the3Cgrp.zupptrade.agent2.repository.TradeRepository;
import com.the3Cgrp.zupptrade.agent2.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.util.JsonUtil;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeConfirmRequestDto;
import com.the3Cgrp.zupptrade.agent2.domain.model.TradeSummary;
import com.the3Cgrp.zupptrade.shared.enums.ConfirmAction;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * confirm() behaviour around the exit ladder.
 *
 * The T1/T2/T3 exit ladder is ALGORITHM-LOCKED (June 2026): it is a 70/64/57-style ladder scaled to
 * the entry seller PoP and recomputed live by Agent 3. Users may adjust lots/strikes but NOT the exit
 * levels — any user-supplied overrideThresholds are ignored (no threshold JSON is rewritten).
 *
 * Covered:
 *  - Null overrideThresholds → no threshold write (unchanged).
 *  - User-supplied overrideThresholds (PE / CE) → IGNORED, no threshold write, no exception, CONFIRMED.
 *  - Iron Condor overrideThresholds → IGNORED.
 *  - Manual override (new strikes) still builds legs of the correct option type (bear call = CE).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfirmThresholdOverrideTest {

    @Mock private TradeRepository          tradeRepository;
    @Mock private JsonUtil                 jsonUtil;
    @Mock private TradeLedgerService       ledger;
    @Mock private Agent1SignalRepository   signalRepository;
    @Mock private UserProfileRepository    userProfileRepository;
    @Mock private ReferenceDataRepository  referenceDataRepository;
    @Mock private OptionChainClient        optionChainClient;
    @Mock private MarketDataClient         marketDataClient;
    @Mock private RecommendationEngine                                              engine;
    @Mock private VolatilityService                                                 volatilityService;
    @Mock private com.the3Cgrp.zupptrade.agent2.engine.math.BlackScholesCalculator blackScholes;
    @Mock private TradingConfig tradingConfig;

    private RecommendationService service;

    private static final int PE_SHORT_STRIKE = 23700;
    private static final int CE_SHORT_STRIKE = 23800;

    @BeforeEach
    void setUp() {
        when(tradingConfig.isHardGateEnabled()).thenReturn(true);
        service = new RecommendationService(
                signalRepository, userProfileRepository, tradeRepository,
                referenceDataRepository, optionChainClient, marketDataClient,
                engine, volatilityService, blackScholes, jsonUtil, ledger, tradingConfig);
    }

    @Test
    void confirm_nullOverrideThresholds_noThresholdWritten() {
        UUID tradeId = UUID.randomUUID();
        TradeEntity trade = buildPeShortTrade();
        when(tradeRepository.findById(tradeId)).thenReturn(Optional.of(trade));
        stubForTradeCardDtoFromEntity(List.of(peShortLeg(), peLongLeg()));
        when(tradeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.confirm(new TradeConfirmRequestDto(tradeId, ConfirmAction.CONFIRM, null, null, null));

        verify(jsonUtil, never()).toJson(any(MonitorThresholdsDto.class));
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CONFIRMED);
    }

    @Test
    void confirm_userThresholdOverridePeShort_ignored_noThresholdWritten() {
        UUID tradeId = UUID.randomUUID();
        TradeEntity trade = buildPeShortTrade();
        when(tradeRepository.findById(tradeId)).thenReturn(Optional.of(trade));
        stubForTradeCardDtoFromEntity(List.of(peShortLeg(), peLongLeg()));
        when(tradeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // User tries to set their own T1/T2/T3 — must be ignored (ladder is algorithm-locked).
        service.confirm(new TradeConfirmRequestDto(tradeId, ConfirmAction.CONFIRM, null,
                new TradeConfirmRequestDto.OverrideThresholds(23900, 23800, 23700), null));

        verify(jsonUtil, never()).toJson(any(MonitorThresholdsDto.class));
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CONFIRMED);
    }

    @Test
    void confirm_userThresholdOverrideCeShort_ignored_noException() {
        UUID tradeId = UUID.randomUUID();
        TradeEntity trade = buildCeShortTrade();
        when(tradeRepository.findById(tradeId)).thenReturn(Optional.of(trade));
        stubForTradeCardDtoFromEntity(List.of(ceShortLeg(), ceLongLeg()));
        when(tradeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Previously an "invalid" T3 above the CE short would throw — now overrides are simply ignored.
        assertThatNoException().isThrownBy(() -> service.confirm(new TradeConfirmRequestDto(
                tradeId, ConfirmAction.CONFIRM, null,
                new TradeConfirmRequestDto.OverrideThresholds(23700, 23750, 23900), null)));

        verify(jsonUtil, never()).toJson(any(MonitorThresholdsDto.class));
    }

    @Test
    void confirm_ironCondorThresholdOverride_ignored() {
        UUID tradeId = UUID.randomUUID();
        TradeEntity trade = buildIronCondorTrade();
        when(tradeRepository.findById(tradeId)).thenReturn(Optional.of(trade));
        stubForTradeCardDtoFromEntity(List.of(peShortLeg(), peLongLeg(), ceShortLeg(), ceLongLeg()));
        when(tradeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.confirm(new TradeConfirmRequestDto(tradeId, ConfirmAction.CONFIRM, null,
                new TradeConfirmRequestDto.OverrideThresholds(23000, 22900, 22800), null));

        verify(jsonUtil, never()).toJson(any(MonitorThresholdsDto.class));
    }

    // ── Manual override still builds legs of the correct option type (bear call = CE) ──

    @Test
    void confirm_manualOverride_bearCallSpread_buildsCeLegsNotPuts() {
        UUID tradeId = UUID.randomUUID();
        TradeEntity trade = buildCeShortTrade();       // BEAR_CALL_SPREAD
        trade.setStatus(TradeStatus.REJECTED);         // skip-decision reject — override path allows REJECTED
        when(tradeRepository.findById(tradeId)).thenReturn(Optional.of(trade));
        stubForTradeCardDtoFromEntity(List.of(ceShortLeg(), ceLongLeg()));
        when(jsonUtil.toJson(any())).thenReturn("JSON");
        when(tradeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TradeConfirmRequestDto.OverrideParams ov = new TradeConfirmRequestDto.OverrideParams(
                24600, 24800, null, null, 10,
                new BigDecimal("60.00"), new BigDecimal("30.00"), null, null,
                "CE_SHORT_KEY", "CE_LONG_KEY", null, null,
                new BigDecimal("30.00"), new BigDecimal("85.00"),
                new BigDecimal("50000"), new BigDecimal("130000"), new BigDecimal("65000"),
                new BigDecimal("1.20"), OptionType.CE);

        service.confirm(new TradeConfirmRequestDto(tradeId, ConfirmAction.CONFIRM, null, null, ov));

        List<TradeLegDto> legs = captureWrittenLegs();
        assertThat(legs).hasSize(2);
        assertThat(legs).allMatch(l -> l.optionType() == OptionType.CE);   // NOT PE
        assertThat(legs).anyMatch(l -> l.action() == LegAction.SELL && l.strike() == 24600);
        assertThat(legs).anyMatch(l -> l.action() == LegAction.BUY  && l.strike() == 24800);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<TradeLegDto> captureWrittenLegs() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(jsonUtil, atLeastOnce()).toJson(captor.capture());
        return captor.getAllValues().stream()
                .filter(v -> v instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof TradeLegDto)
                .map(v -> (List<TradeLegDto>) v)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No List<TradeLegDto> was passed to toJson"));
    }

    @SuppressWarnings("unchecked")
    private void stubForTradeCardDtoFromEntity(List<TradeLegDto> legs) {
        doAnswer(invocation -> {
            String json = (String) invocation.getArgument(0);
            return "GATES_JSON".equals(json) ? List.of() : legs;
        }).when(jsonUtil).fromJson(anyString(), any(TypeReference.class));

        when(jsonUtil.fromJson(anyString(), eq(MonitorThresholdsDto.class)))
                .thenReturn(existingPeThresholds());
        when(jsonUtil.fromJson(anyString(), eq(TradeSummary.class)))
                .thenReturn(emptySummary());
    }

    private TradeEntity buildPeShortTrade() {
        TradeEntity trade = new TradeEntity();
        trade.setStatus(TradeStatus.PENDING_CONFIRM);
        trade.setValidUntil(LocalDateTime.now().plusHours(1));
        trade.setStrategy(Strategy.BULL_PUT_SPREAD);
        trade.setLegs("LEGS_JSON");
        trade.setThresholds("THRESHOLDS_JSON");
        trade.setSummary("SUMMARY_JSON");
        trade.setGateResults("GATES_JSON");
        trade.setGeneratedAt(LocalDateTime.now());
        trade.setTradeCode("T-20260626-0001");
        return trade;
    }

    private TradeEntity buildCeShortTrade() {
        TradeEntity trade = buildPeShortTrade();
        trade.setStrategy(Strategy.BEAR_CALL_SPREAD);
        return trade;
    }

    private TradeEntity buildIronCondorTrade() {
        TradeEntity trade = buildPeShortTrade();
        trade.setStrategy(Strategy.IRON_CONDOR);
        return trade;
    }

    private TradeLegDto peShortLeg() {
        return new TradeLegDto(OptionType.PE, PE_SHORT_STRIKE, new BigDecimal("60.00"),
                LegAction.SELL, new BigDecimal("-0.20"), new BigDecimal("0.15"),
                "NFO_OPT|NIFTY|2026-07-01|23700|PE");
    }

    private TradeLegDto peLongLeg() {
        return new TradeLegDto(OptionType.PE, PE_SHORT_STRIKE - 100, new BigDecimal("35.00"),
                LegAction.BUY, new BigDecimal("-0.12"), new BigDecimal("0.08"),
                "NFO_OPT|NIFTY|2026-07-01|23600|PE");
    }

    private TradeLegDto ceShortLeg() {
        return new TradeLegDto(OptionType.CE, CE_SHORT_STRIKE, new BigDecimal("60.00"),
                LegAction.SELL, new BigDecimal("0.20"), new BigDecimal("0.15"),
                "NFO_OPT|NIFTY|2026-07-01|23800|CE");
    }

    private TradeLegDto ceLongLeg() {
        return new TradeLegDto(OptionType.CE, CE_SHORT_STRIKE + 100, new BigDecimal("35.00"),
                LegAction.BUY, new BigDecimal("0.12"), new BigDecimal("0.08"),
                "NFO_OPT|NIFTY|2026-07-01|23900|CE");
    }

    private MonitorThresholdsDto existingPeThresholds() {
        return MonitorThresholdsDto.twoLeg(
                new BigDecimal("23800"), new BigDecimal("23750"), new BigDecimal("23700"),
                new BigDecimal("50000"), new BigDecimal("100000"));
    }

    private TradeSummary emptySummary() {
        return new TradeSummary(
                BigDecimal.ZERO, 0, 65,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
