package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.agent2.client.MarketDataClient;
import com.the3Cgrp.zupptrade.agent2.client.OptionChainClient;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.domain.entity.TradeEntity;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationEngine;
import com.the3Cgrp.zupptrade.agent2.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.agent2.repository.ReferenceDataRepository;
import com.the3Cgrp.zupptrade.agent2.repository.TradeRepository;
import com.the3Cgrp.zupptrade.agent2.repository.UserProfileRepository;
import com.the3Cgrp.zupptrade.agent2.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.shared.dto.RecommendRequestDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeConfirmRequestDto;
import com.the3Cgrp.zupptrade.shared.enums.ConfirmAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 write ownership: a user may only recommend under their own profile and confirm their own
 * trade (admin may act for any). The guard runs at the top of each write, so a cross-user caller is
 * rejected before any engine/persistence work. Happy-path recommend/confirm are covered elsewhere.
 */
class RecommendationOwnershipTest {

    private final Agent1SignalRepository signalRepository = mock(Agent1SignalRepository.class);
    private final UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final ReferenceDataRepository referenceDataRepository = mock(ReferenceDataRepository.class);
    private final OptionChainClient optionChainClient = mock(OptionChainClient.class);
    private final MarketDataClient marketDataClient = mock(MarketDataClient.class);
    private final RecommendationEngine engine = mock(RecommendationEngine.class);
    private final VolatilityService volatilityService = mock(VolatilityService.class);
    private final com.the3Cgrp.zupptrade.agent2.engine.math.BlackScholesCalculator blackScholes =
            mock(com.the3Cgrp.zupptrade.agent2.engine.math.BlackScholesCalculator.class);
    private final JsonUtil jsonUtil = mock(JsonUtil.class);
    private final TradeLedgerService ledger = mock(TradeLedgerService.class);
    private final TradingConfig config = mock(TradingConfig.class);

    private final UserContext userContext = new UserContext();
    private final OwnershipGuard guard = new OwnershipGuard(userContext);

    private final RecommendationService service = new RecommendationService(
            signalRepository, userProfileRepository, tradeRepository, referenceDataRepository,
            optionChainClient, marketDataClient, engine, volatilityService, blackScholes,
            jsonUtil, ledger, config, Clock.systemDefaultZone(), guard);

    private final UUID alice = UUID.randomUUID();   // owner
    private final UUID bob   = UUID.randomUUID();   // intruder

    private void loginAs(UUID id, boolean admin) {
        userContext.set(new AuthenticatedUser(id, "LIVE", admin, "UPSTOX"));
    }

    @AfterEach
    void clear() { userContext.clear(); }

    // ── recommend ───────────────────────────────────────────────────────────────

    @Test
    void recommend_forAnotherUsersProfile_throws403_beforeAnyLookup() {
        loginAs(bob, false);
        RecommendRequestDto request = RecommendRequestDto.standard(alice, UUID.randomUUID());

        assertThatThrownBy(() -> service.recommend(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(signalRepository, never()).findById(any());
    }

    @Test
    void recommend_anonymous_throws401() {
        RecommendRequestDto request = RecommendRequestDto.standard(alice, UUID.randomUUID());

        assertThatThrownBy(() -> service.recommend(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── confirm ─────────────────────────────────────────────────────────────────

    @Test
    void confirm_anotherUsersTrade_throws403() {
        loginAs(bob, false);
        UUID tradeId = UUID.randomUUID();
        TradeEntity trade = mock(TradeEntity.class);
        UserProfileEntity owner = mock(UserProfileEntity.class);
        when(owner.getId()).thenReturn(alice);
        when(trade.getUserProfile()).thenReturn(owner);
        when(tradeRepository.findById(tradeId)).thenReturn(Optional.of(trade));

        assertThatThrownBy(() -> service.confirm(
                new TradeConfirmRequestDto(tradeId, ConfirmAction.CONFIRM, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(tradeRepository, never()).save(any());
    }
}
