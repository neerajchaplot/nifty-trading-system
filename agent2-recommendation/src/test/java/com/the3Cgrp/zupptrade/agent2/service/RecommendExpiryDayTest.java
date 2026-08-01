package com.the3Cgrp.zupptrade.agent2.service;

import tools.jackson.core.type.TypeReference;
import com.the3Cgrp.zupptrade.agent2.client.MarketDataClient;
import com.the3Cgrp.zupptrade.agent2.client.OptionChainClient;
import com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.ReferenceDataEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.domain.model.TradeSummary;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationEngine;
import com.the3Cgrp.zupptrade.agent2.exception.MarketDataUnavailableException;
import com.the3Cgrp.zupptrade.agent2.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.agent2.repository.ReferenceDataRepository;
import com.the3Cgrp.zupptrade.agent2.repository.TradeRepository;
import com.the3Cgrp.zupptrade.agent2.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.agent2.util.JsonUtil;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.dto.RecommendRequestDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the expiry-day guard in RecommendationService.recommend():
 * when DTE = 0, no trade is built — the request is rejected up front with a clear message,
 * and the 5-layer engine is never invoked (so the old Layer-5 divide-by-zero cannot occur).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendExpiryDayTest {

    @Mock private Agent1SignalRepository   signalRepository;
    @Mock private UserProfileRepository    userProfileRepository;
    @Mock private TradeRepository          tradeRepository;
    @Mock private ReferenceDataRepository  referenceDataRepository;
    @Mock private OptionChainClient        optionChainClient;
    @Mock private MarketDataClient         marketDataClient;
    @Mock private RecommendationEngine     engine;
    @Mock private VolatilityService        volatilityService;
    @Mock private com.the3Cgrp.zupptrade.agent2.engine.math.BlackScholesCalculator blackScholes;
    @Mock private JsonUtil                 jsonUtil;
    @Mock private TradeLedgerService       ledger;
    @Mock private TradingConfig            tradingConfig;
    @Mock private ReferenceDataEntity      lotSizeRef;

    private RecommendationService service;

    private static final UUID SIGNAL_ID  = UUID.randomUUID();
    private static final UUID PROFILE_ID  = UUID.randomUUID();
    private static final LocalDate TODAY  = LocalDate.now();   // DTE = 0

    @BeforeEach
    void setUp() {
        service = new RecommendationService(
                signalRepository, userProfileRepository, tradeRepository,
                referenceDataRepository, optionChainClient, marketDataClient,
                engine, volatilityService, blackScholes, jsonUtil, ledger, tradingConfig,
                java.time.Clock.systemDefaultZone());
    }

    @Test
    void recommend_onExpiryDay_rejectsWithMessage_andNeverRunsEngine() {
        Agent1SignalEntity signal = new Agent1SignalEntity();
        signal.setExpiryDate(TODAY);                 // → DTE = 0
        signal.setVixLevel(new BigDecimal("15"));
        signal.setVixRegime(VixRegime.NORMAL);

        when(signalRepository.findById(SIGNAL_ID)).thenReturn(Optional.of(signal));
        when(userProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(new UserProfileEntity()));

        // Lot size lookup
        when(referenceDataRepository.findById("nifty.lot.size")).thenReturn(Optional.of(lotSizeRef));
        when(lotSizeRef.isExpired()).thenReturn(false);
        when(lotSizeRef.getValue()).thenReturn("{\"lotSize\":65}");
        doReturn(Map.of("lotSize", 65)).when(jsonUtil).fromJson(anyString(), eq(Map.class));

        // Market data: option chain gives spot; live snapshot unavailable (weekend/holiday path)
        when(optionChainClient.fetch(any())).thenReturn(new OptionChainData(
                new BigDecimal("24000"), TODAY, List.of(), List.of(), 24000, BigDecimal.ZERO, BigDecimal.ZERO));
        when(marketDataClient.fetchSnapshot()).thenThrow(new MarketDataUnavailableException("market closed"));

        // Persistence + JSON round-trip used by the no-trade builder
        when(tradingConfig.isHardGateEnabled()).thenReturn(true);
        when(jsonUtil.toJson(any())).thenReturn("JSON");
        when(tradeRepository.nextTradeCodeSeq()).thenReturn(1L);
        when(tradeRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(jsonUtil.fromJson(anyString(), eq(TradeSummary.class))).thenReturn(emptySummary());
        when(jsonUtil.fromJson(anyString(), eq(MonitorThresholdsDto.class)))
                .thenReturn(MonitorThresholdsDto.twoLeg(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO));
        doReturn(List.of()).when(jsonUtil).fromJson(anyString(), any(TypeReference.class));

        TradeCardDto card = service.recommend(new RecommendRequestDto(SIGNAL_ID, PROFILE_ID, null));

        // Rejected with the expiry-day message, DTE=0, and the engine was never run
        assertThat(card.status()).isEqualTo(TradeStatus.REJECTED);
        assertThat(card.dte()).isZero();
        assertThat(card.rationale())
                .contains("expiry day")
                .contains("DTE=0");
        verify(engine, never()).execute(any());
        verify(tradeRepository).saveAndFlush(any());
    }

    private TradeSummary emptySummary() {
        return new TradeSummary(
                BigDecimal.ZERO, 0, 65,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
