package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.pipeline.ScoringPipeline;
import com.the3Cgrp.zupptrade.agent1.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.core.expiry.ExpiryDateService;
import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import com.the3Cgrp.zupptrade.shared.enums.SignalSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * /latest scoping by channel + user. TRADING: a normal user reads only their own; an admin reads
 * global; anonymous → 401. FUTURES: always global (admin-driven, shared) — never user-scoped.
 * Empty results are used so the test asserts which repository method was chosen.
 */
class Agent1ServiceScopingTest {

    private final ScoringPipeline pipeline = mock(ScoringPipeline.class);
    private final Agent1SignalRepository repository = mock(Agent1SignalRepository.class);
    private final ExpiryDateService expiryDateService = mock(ExpiryDateService.class);

    private final UserContext userContext = new UserContext();
    private final OwnershipGuard guard = new OwnershipGuard(userContext);

    private final Agent1Service service =
            new Agent1Service(pipeline, repository, expiryDateService, guard);

    private final UUID alice = UUID.randomUUID();
    private final LocalDate expiry = LocalDate.of(2026, 6, 2);

    private void loginAs(UUID id, boolean admin) {
        userContext.set(new AuthenticatedUser(id, "LIVE", admin, "UPSTOX"));
    }

    @AfterEach
    void clear() { userContext.clear(); }

    @Test
    void trading_normalUser_usesUserScopedSourceQuery() {
        loginAs(alice, false);
        when(repository.findTopByExpiryDateAndUserProfileIdAndSourceAndStatusOrderByTimestampDesc(
                expiry, alice, SignalSource.TRADING, "ACTIVE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.latest(expiry, SignalSource.TRADING))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository).findTopByExpiryDateAndUserProfileIdAndSourceAndStatusOrderByTimestampDesc(
                expiry, alice, SignalSource.TRADING, "ACTIVE");
    }

    @Test
    void trading_admin_usesGlobalSourceQuery() {
        loginAs(alice, true);
        when(repository.findTopByExpiryDateAndSourceAndStatusOrderByTimestampDesc(
                expiry, SignalSource.TRADING, "ACTIVE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.latest(expiry, SignalSource.TRADING))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository).findTopByExpiryDateAndSourceAndStatusOrderByTimestampDesc(
                expiry, SignalSource.TRADING, "ACTIVE");
    }

    @Test
    void trading_anonymous_throws401() {
        assertThatThrownBy(() -> service.latest(expiry, SignalSource.TRADING))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void futures_isGlobal_ignoresUser() {
        loginAs(alice, false); // even a normal user gets the shared FUTURES signal, not their own
        when(repository.findTopByExpiryDateAndSourceAndStatusOrderByTimestampDesc(
                expiry, SignalSource.FUTURES, "ACTIVE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.latest(expiry, SignalSource.FUTURES))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository).findTopByExpiryDateAndSourceAndStatusOrderByTimestampDesc(
                expiry, SignalSource.FUTURES, "ACTIVE");
        verify(repository, never()).findTopByExpiryDateAndUserProfileIdAndSourceAndStatusOrderByTimestampDesc(
                any(), any(), any(), any());
    }
}
