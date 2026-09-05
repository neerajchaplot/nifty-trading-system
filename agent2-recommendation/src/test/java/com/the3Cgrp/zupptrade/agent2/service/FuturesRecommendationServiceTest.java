package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.agent2.config.FuturesConfig;
import com.the3Cgrp.zupptrade.agent2.client.Agent1ScoreClient;
import com.the3Cgrp.zupptrade.agent2.domain.entity.FutureTradeLedgerEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.ReferenceDataEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.engine.futures.*;
import com.the3Cgrp.zupptrade.agent2.repository.FutureTradeLedgerRepository;
import com.the3Cgrp.zupptrade.agent2.repository.ReferenceDataRepository;
import com.the3Cgrp.zupptrade.agent2.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.agent2.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient.UpstoxCandle;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxMarketQuoteClient;
import com.the3Cgrp.zupptrade.shared.dto.*;
import com.the3Cgrp.zupptrade.shared.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Orchestration test for the futures recommend/confirm flow — real engine + real JSON,
 * mocked repositories and Upstox clients. Anchored to the 31-Jul worked example.
 */
class FuturesRecommendationServiceTest {

    private Agent1ScoreClient agent1ScoreClient;
    private FuturesCommentaryReader commentaryReader;
    private UserProfileRepository profileRepo;
    private ReferenceDataRepository refRepo;
    private FutureTradeLedgerRepository ledgerRepo;
    private UpstoxHistoricalDataClient historicalClient;
    private UpstoxMarketQuoteClient marketQuoteClient;
    private FuturesInstrumentResolver instrumentResolver;

    private FuturesRecommendationService service;
    private final JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
    private final FuturesConfig cfg = new FuturesConfig();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-03T04:00:00Z"), ZoneOffset.UTC);

    private final UUID signalId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agent1ScoreClient = mock(Agent1ScoreClient.class);
        commentaryReader = mock(FuturesCommentaryReader.class);
        profileRepo = mock(UserProfileRepository.class);
        refRepo = mock(ReferenceDataRepository.class);
        ledgerRepo = mock(FutureTradeLedgerRepository.class);
        historicalClient = mock(UpstoxHistoricalDataClient.class);
        marketQuoteClient = mock(UpstoxMarketQuoteClient.class);
        instrumentResolver = mock(FuturesInstrumentResolver.class);

        FuturesPlanEngine engine = new FuturesPlanEngine(
                new CamarillaCalculator(), new OpenClassifier(), new FourArmGridBuilder(),
                new ArmSelector(), new ProbabilityRanker(), new RiskRewardCalculator(),
                new CostModel(), new CompressionGate(), new PositionSizer(), new MarginEstimator(),
                new ConfidenceGate());

        // Clock is 09:30 IST → SessionOpenResolver takes the actual-open branch (today's daily open).
        SessionOpenResolver sessionOpenResolver =
                new SessionOpenResolver(marketQuoteClient, cfg, clock);

        service = new FuturesRecommendationService(agent1ScoreClient, commentaryReader, profileRepo, refRepo,
                ledgerRepo, historicalClient, sessionOpenResolver, marketQuoteClient,
                new ArmReachabilityCalculator(), engine, instrumentResolver, cfg, jsonUtil, clock);

        // Mandatory admin commentary present → Agent 1 regenerates a fresh signal from it.
        when(commentaryReader.findCommentary(any())).thenReturn(Optional.of("admin commentary"));
        Agent1SignalDto signal = new Agent1SignalDto(
                signalId, OffsetDateTime.now(clock), null, Bias.BULLISH, Strength.MILD,
                new BigDecimal("0.30"), new BigDecimal("0.80"), Confidence.HIGH,
                null, null, null, null, false, null, null, null, null);
        when(agent1ScoreClient.score(anyString())).thenReturn(signal);

        UserProfileEntity profile = mock(UserProfileEntity.class);
        when(profile.getId()).thenReturn(profileId);
        when(profile.getCapital()).thenReturn(new BigDecimal("500000"));
        when(profileRepo.findById(profileId)).thenReturn(Optional.of(profile));

        ReferenceDataEntity lot = new ReferenceDataEntity();
        lot.setValue("{\"lotSize\":65}");
        lot.setFetchedAt(LocalDateTime.now());
        lot.setTtlHours(24);
        when(refRepo.findById("nifty.lot.size")).thenReturn(Optional.of(lot));

        when(historicalClient.fetchNiftyDailyCandles(anyInt())).thenReturn(workedExampleCandles());
        // Live level inside the worked-example rotation band → arms judged REACHABLE by default.
        when(marketQuoteClient.fetchNiftySpot()).thenReturn(new BigDecimal("24300"));
        when(instrumentResolver.resolveCurrentMonthFut()).thenReturn("NSE_FO|54321");
        when(ledgerRepo.countByTradeDate(any())).thenReturn(0L);
        when(ledgerRepo.countByUserProfileIdAndTradeDateAndStatusIn(any(), any(), any())).thenReturn(0L);
        when(ledgerRepo.save(any(FutureTradeLedgerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Most-recent completed candle = the 31-Jul worked prior day; older ones ~150 range. */
    private List<UpstoxCandle> workedExampleCandles() {
        List<UpstoxCandle> candles = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        // Today's forming candle — its open (24300) is the actual session open post-9:15 (→ RANGE zone).
        candles.add(new UpstoxCandle(today,
                new BigDecimal("24300"), new BigDecimal("24350"),
                new BigDecimal("24280"), new BigDecimal("24320"), 0L));
        candles.add(new UpstoxCandle(today.minusDays(1),
                new BigDecimal("24250"), new BigDecimal("24341.39"),
                new BigDecimal("24196.81"), new BigDecimal("24317.16"), 0L));
        for (int i = 2; i <= 25; i++) {
            candles.add(new UpstoxCandle(today.minusDays(i),
                    new BigDecimal("24000"), new BigDecimal("24100"),
                    new BigDecimal("23950"), new BigDecimal("24050"), 0L)); // range 150
        }
        return candles;
    }

    @Test
    void recommend_bullishRange_persistsPrimedCard_withRecommendedRotation() {
        FuturesPlanCardDto card = service.recommend(
                new FuturesRecommendRequestDto(signalId, profileId, 900));

        assertThat(card.status()).isEqualTo(FuturePlanStatus.PRIMED);
        assertThat(card.openZone()).isEqualTo(OpenZone.RANGE);
        assertThat(card.primaryArm()).isEqualTo(FutureArmType.LONG_ROTATION);
        assertThat(card.instrumentKey()).isEqualTo("NSE_FO|54321");
        assertThat(card.arms()).hasSize(4);
        assertThat(card.planCode()).startsWith("FUT-20260803-");

        FuturesArmCardDto longRot = card.arms().stream()
                .filter(a -> a.armType() == FutureArmType.LONG_ROTATION).findFirst().orElseThrow();
        assertThat(longRot.status()).isEqualTo(ArmCardStatus.RECOMMENDED);
        assertThat(longRot.label()).isEqualTo("Buy the dip");
        assertThat(longRot.rrGross()).isEqualByComparingTo("2.0");
        assertThat(longRot.lots()).isEqualTo(1);

        // Reachability overlay: live level 24300 is inside the rotation band → REACHABLE, and the
        // card carries the level it was judged against.
        assertThat(card.currentLevel()).isEqualByComparingTo("24300");
        assertThat(longRot.reachability()).isEqualTo(ArmReachability.REACHABLE);

        verify(ledgerRepo).save(any(FutureTradeLedgerEntity.class));
    }

    @Test
    void confirm_missedArm_isRejected() {
        // Live level 24400 is above the long-rotation target (24356.92) → the arm is MISSED.
        when(marketQuoteClient.fetchNiftySpot()).thenReturn(new BigDecimal("24400"));
        FutureTradeLedgerEntity plan = primedPlanWithArms();
        UUID planId = UUID.randomUUID();
        when(ledgerRepo.findById(planId)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.confirm(new FuturesConfirmRequestDto(
                planId, ConfirmAction.CONFIRM, FutureArmType.LONG_ROTATION, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missed");
        assertThat(plan.getStatus()).isEqualTo(FuturePlanStatus.PRIMED); // unchanged — not armed
    }

    @Test
    void recommend_killSwitchTripped_marksNoTrade() {
        when(ledgerRepo.countByUserProfileIdAndTradeDateAndStatusIn(eq(profileId), any(), any()))
                .thenReturn(3L); // == maxTradesPerDay default

        FuturesPlanCardDto card = service.recommend(
                new FuturesRecommendRequestDto(signalId, profileId, 900));

        assertThat(card.status()).isEqualTo(FuturePlanStatus.NO_TRADE);
        assertThat(card.noTradeReason()).contains("kill-switch");
    }

    @Test
    void recommend_missingAdminCommentary_isRejected() {
        when(commentaryReader.findCommentary(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recommend(new FuturesRecommendRequestDto(signalId, profileId, 900)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("commentary required");
        verify(agent1ScoreClient, never()).score(anyString());
    }

    @Test
    void confirm_approve_armsChosenLeg() {
        FutureTradeLedgerEntity plan = primedPlanWithArms();
        UUID planId = UUID.randomUUID();
        when(ledgerRepo.findById(planId)).thenReturn(Optional.of(plan));

        FuturesPlanCardDto card = service.confirm(new FuturesConfirmRequestDto(
                planId, ConfirmAction.CONFIRM, FutureArmType.LONG_ROTATION, null));

        assertThat(plan.getStatus()).isEqualTo(FuturePlanStatus.ARMED);
        assertThat(plan.getPrimaryArm()).isEqualTo(FutureArmType.LONG_ROTATION);
        assertThat(plan.getEntryPrice()).isEqualByComparingTo("24277.40");
        assertThat(plan.getApprovedAt()).isNotNull();
        assertThat(card.status()).isEqualTo(FuturePlanStatus.ARMED);
    }

    @Test
    void confirm_reject_marksRejected() {
        FutureTradeLedgerEntity plan = primedPlanWithArms();
        UUID planId = UUID.randomUUID();
        when(ledgerRepo.findById(planId)).thenReturn(Optional.of(plan));

        service.confirm(new FuturesConfirmRequestDto(planId, ConfirmAction.REJECT, null, null));

        assertThat(plan.getStatus()).isEqualTo(FuturePlanStatus.REJECTED);
    }

    /** A PRIMED entity carrying a serialized four-arm card (LONG_ROTATION selectable). */
    private FutureTradeLedgerEntity primedPlanWithArms() {
        FutureTradeLedgerEntity plan = new FutureTradeLedgerEntity();
        plan.setPlanCode("FUT-20260803-001");
        plan.setStatus(FuturePlanStatus.PRIMED);
        plan.setBias(Bias.BULLISH);
        plan.setTradeDate(LocalDate.now(clock));
        plan.setCamarilla(jsonUtil.toJson(new FuturesCamarillaDto(
                new BigDecimal("144.58"), new BigDecimal("24285.12"), new BigDecimal("24356.92"),
                new BigDecimal("24396.68"), new BigDecimal("24277.40"), new BigDecimal("24237.64"))));
        plan.setPriorOhlc(jsonUtil.toJson(new FuturesPriorOhlcDto(
                LocalDate.now(clock).minusDays(1), new BigDecimal("24250"), new BigDecimal("24341.39"),
                new BigDecimal("24196.81"), new BigDecimal("24317.16"))));
        plan.setGateResults(jsonUtil.toJson(java.util.Map.of(
                "confidenceGatePassed", true, "minConfidence", new BigDecimal("0.40"),
                "compressed", false, "compressionThreshold", new BigDecimal("0.70"))));
        List<FuturesArmCardDto> arms = List.of(new FuturesArmCardDto(
                FutureArmType.LONG_ROTATION, "Buy the dip", TradeDirection.LONG,
                ArmCardStatus.RECOMMENDED, null,
                new BigDecimal("24277.40"), new BigDecimal("24237.64"), new BigDecimal("24356.92"),
                new BigDecimal("39.76"), new BigDecimal("79.52"), new BigDecimal("2.0"),
                new BigDecimal("1.8"), new BigDecimal("8.40"), new BigDecimal("45.3"),
                1, 65, new BigDecimal("2584.40"), new BigDecimal("2584.40"),
                new BigDecimal("189363.72"), new BigDecimal("1578031.00"), null));
        plan.setFourArms(jsonUtil.toJson(arms));
        return plan;
    }
}
