package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.pipeline.ScoringPipeline;
import com.the3Cgrp.zupptrade.agent1.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.core.expiry.ExpiryDateService;
import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5: /latest scopes to the caller. A normal user reads only their own signal for an expiry;
 * an admin reads the latest across all users; anonymous → 401. Empty results are used so the test
 * asserts which repository method was chosen without needing a full entity.
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
    void latest_normalUser_usesScopedQuery() {
        loginAs(alice, false);
        when(repository.findTopByExpiryDateAndUserProfileIdAndStatusOrderByTimestampDesc(expiry, alice, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.latest(expiry))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository).findTopByExpiryDateAndUserProfileIdAndStatusOrderByTimestampDesc(expiry, alice, "ACTIVE");
        verify(repository, never()).findTopByExpiryDateAndStatusOrderByTimestampDesc(expiry, "ACTIVE");
    }

    @Test
    void latest_admin_usesUnscopedQuery() {
        loginAs(alice, true);
        when(repository.findTopByExpiryDateAndStatusOrderByTimestampDesc(expiry, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.latest(expiry))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository).findTopByExpiryDateAndStatusOrderByTimestampDesc(expiry, "ACTIVE");
        verify(repository, never())
                .findTopByExpiryDateAndUserProfileIdAndStatusOrderByTimestampDesc(expiry, alice, "ACTIVE");
    }

    @Test
    void latest_anonymous_throws401() {
        assertThatThrownBy(() -> service.latest(expiry))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
