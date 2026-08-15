package com.the3Cgrp.zupptrade.agent2.service;

import tools.jackson.core.type.TypeReference;
import com.the3Cgrp.zupptrade.agent2.client.MarketDataClient;
import com.the3Cgrp.zupptrade.agent2.client.OptionChainClient;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.domain.entity.TradeEntity;
import com.the3Cgrp.zupptrade.agent2.domain.model.TradeSummary;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationEngine;
import com.the3Cgrp.zupptrade.agent2.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.agent2.repository.ReferenceDataRepository;
import com.the3Cgrp.zupptrade.agent2.repository.TradeRepository;
import com.the3Cgrp.zupptrade.agent2.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.agent2.util.JsonUtil;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Value-level coverage for buildMonitorConfig's Iron Condor branch — the seam the credit-ladder
 * migration missed. Regression for the bug where the IC monitor config placed T3 exactly ON the
 * short strike (0-pt gap) and dropped entryPop (so Agent 3 could never recompute the ladder).
 *
 * Both paths must satisfy the invariants:
 *   - T3 sits ≥ 75 pts from each short strike (PE side above, CE side below), and
 *   - entryPopDown / entryPopUp are present (so Agent 3 recomputes the ladder live).
 * The old code failed BOTH.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IronCondorMonitorConfigTest {

    @Mock private TradeRepository          tradeRepository;
    @Mock private JsonUtil                 jsonUtil;
    @Mock private TradeLedgerService       ledger;
    @Mock private Agent1SignalRepository   signalRepository;
    @Mock private UserProfileRepository    userProfileRepository;
    @Mock private ReferenceDataRepository  referenceDataRepository;
    @Mock private OptionChainClient        optionChainClient;
    @Mock private MarketDataClient         marketDataClient;
    @Mock private RecommendationEngine     engine;
    @Mock private VolatilityService        volatilityService;
    @Mock private com.the3Cgrp.zupptrade.agent2.engine.math.BlackScholesCalculator blackScholes;
    @Mock private TradingConfig            tradingConfig;

    private RecommendationService service;

    private static final int PE_SHORT = 24000, PE_LONG = 23850;
    private static final int CE_SHORT = 24800, CE_LONG = 24950;

    // Fills (from a real IC card): PE 17.85/9.15, CE 22.80/9.70 → net 21.80
    private static final BigDecimal PE_SELL = new BigDecimal("17.85");
    private static final BigDecimal PE_BUY  = new BigDecimal("9.15");
    private static final BigDecimal CE_SELL = new BigDecimal("22.80");
    private static final BigDecimal CE_BUY  = new BigDecimal("9.70");

    private final com.the3Cgrp.zupptrade.core.security.UserContext userContext =
            new com.the3Cgrp.zupptrade.core.security.UserContext();

    @BeforeEach
    void setUp() {
        userContext.set(new com.the3Cgrp.zupptrade.core.security.AuthenticatedUser(
                UUID.randomUUID(), "LIVE", true, "UPSTOX"));
        service = new RecommendationService(
                signalRepository, userProfileRepository, tradeRepository,
                referenceDataRepository, optionChainClient, marketDataClient,
                engine, volatilityService, blackScholes, jsonUtil, ledger, tradingConfig,
                Clock.systemDefaultZone(),
                new com.the3Cgrp.zupptrade.core.security.OwnershipGuard(userContext));
    }

    @Test
    void ic_validStoredLadder_isUsed_t3KeepsFloor_andEntryPopPresent() {
        // Stored ladder from recommend time: PE T3 = strike+75, CE T3 = strike-75, entryPop on both sides.
        MonitorThresholdsDto stored = MonitorThresholdsDto.ironCondorCredit(
                bd(PE_SHORT + 125), bd(PE_SHORT + 100), bd(PE_SHORT + 75),
                bd(CE_SHORT - 125), bd(CE_SHORT - 100), bd(CE_SHORT - 75),
                bd(3474), bd(11583),
                new BigDecimal("0.82"), new BigDecimal("0.80"));
        stubTrade(stored);

        MonitorConfigDto config = service.buildMonitorConfig(UUID.randomUUID(), PE_SELL, PE_BUY, CE_SELL, CE_BUY);

        assertLadderInvariants(config.thresholds());
        // stored values carried through verbatim
        assertThat(config.thresholds().t3ExitNiftyDown()).isEqualByComparingTo(bd(PE_SHORT + 75));
        assertThat(config.thresholds().t3ExitNiftyUp()).isEqualByComparingTo(bd(CE_SHORT - 75));
        assertThat(config.thresholds().entryPopDown()).isEqualByComparingTo("0.82");
    }

    @Test
    void ic_staleStoredLadder_fallsBackTo75ptFloor_withEntryPop() {
        // Override/stale case: zeros, no entryPop (the ironCondor() factory) → must trigger the fallback.
        MonitorThresholdsDto stale = MonitorThresholdsDto.ironCondor(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        stubTrade(stale);

        MonitorConfigDto config = service.buildMonitorConfig(UUID.randomUUID(), PE_SELL, PE_BUY, CE_SELL, CE_BUY);

        assertLadderInvariants(config.thresholds());
        // fallback uses the 125/100/75 floors
        assertThat(config.thresholds().t3ExitNiftyDown()).isEqualByComparingTo(bd(PE_SHORT + 75));  // 24075
        assertThat(config.thresholds().t3ExitNiftyUp()).isEqualByComparingTo(bd(CE_SHORT - 75));    // 24725
        assertThat(config.thresholds().entryPopDown()).isNotNull();
        assertThat(config.thresholds().entryPopUp()).isNotNull();
    }

    /** The two invariants the old code violated: T3 ≥75 pts from each short strike, and entryPop present. */
    private void assertLadderInvariants(MonitorThresholdsDto t) {
        // PE side sits ABOVE the short strike; T3 must be at least 75 pts above.
        assertThat(t.t3ExitNiftyDown().subtract(bd(PE_SHORT)))
                .as("PE T3 gap from short strike")
                .isGreaterThanOrEqualTo(bd(75));
        // CE side sits BELOW the short strike; T3 must be at least 75 pts below.
        assertThat(bd(CE_SHORT).subtract(t.t3ExitNiftyUp()))
                .as("CE T3 gap from short strike")
                .isGreaterThanOrEqualTo(bd(75));
        // Ordering: PE T1 > T2 > T3 ; CE T1 < T2 < T3
        assertThat(t.t1WatchNiftyDown()).isGreaterThan(t.t2ReadjustNiftyDown());
        assertThat(t.t2ReadjustNiftyDown()).isGreaterThan(t.t3ExitNiftyDown());
        assertThat(t.t1WatchNiftyUp()).isLessThan(t.t2ReadjustNiftyUp());
        assertThat(t.t2ReadjustNiftyUp()).isLessThan(t.t3ExitNiftyUp());
        // entryPop carried so Agent 3 can recompute the ladder live
        assertThat(t.entryPopDown()).isNotNull();
        assertThat(t.entryPopUp()).isNotNull();
    }

    // ── Mock wiring ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubTrade(MonitorThresholdsDto stored) {
        TradeEntity trade = new TradeEntity();
        trade.setStatus(TradeStatus.CONFIRMED);
        trade.setStrategy(Strategy.IRON_CONDOR);
        trade.setSpreadDirection(SpreadDirection.CREDIT);
        trade.setExpiryDate(LocalDate.now().plusDays(6));
        trade.setLegs("LEGS_JSON");
        trade.setSummary("SUMMARY_JSON");
        trade.setThresholds("THRESHOLDS_JSON");

        when(tradeRepository.findById(any())).thenReturn(Optional.of(trade));
        when(tradeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jsonUtil.toJson(any())).thenReturn("JSON");

        doReturn(icLegs()).when(jsonUtil).fromJson(anyString(), any(TypeReference.class));
        when(jsonUtil.fromJson(anyString(), eq(TradeSummary.class))).thenReturn(icSummary());
        when(jsonUtil.fromJson(anyString(), eq(MonitorThresholdsDto.class))).thenReturn(stored);
    }

    private List<TradeLegDto> icLegs() {
        return List.of(
                new TradeLegDto(OptionType.PE, PE_SHORT, PE_SELL, LegAction.SELL,
                        new BigDecimal("-0.15"), new BigDecimal("0.15"), "PE_SHORT_KEY"),
                new TradeLegDto(OptionType.PE, PE_LONG, PE_BUY, LegAction.BUY,
                        new BigDecimal("-0.10"), new BigDecimal("0.10"), "PE_LONG_KEY"),
                new TradeLegDto(OptionType.CE, CE_SHORT, CE_SELL, LegAction.SELL,
                        new BigDecimal("0.15"), new BigDecimal("0.15"), "CE_SHORT_KEY"),
                new TradeLegDto(OptionType.CE, CE_LONG, CE_BUY, LegAction.BUY,
                        new BigDecimal("0.10"), new BigDecimal("0.10"), "CE_LONG_KEY"));
    }

    private TradeSummary icSummary() {
        return new TradeSummary(
                new BigDecimal("21.80"), 1, 65,
                new BigDecimal("1417.00"), new BigDecimal("11583.00"), new BigDecimal("5791.50"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static BigDecimal bd(long v) { return BigDecimal.valueOf(v); }
}
