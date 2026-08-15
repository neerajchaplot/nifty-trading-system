package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.repository.MonitoringEvaluationRepository;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5: the UI live-monitor read scopes to the caller. The scheduler's global findAllActive()
 * is a separate method and is intentionally NOT scoped (it must monitor every user's trades).
 */
class ActiveTradesScopingTest {

    private final TradeMonitorReader reader = mock(TradeMonitorReader.class);
    private final MonitoringEvaluationRepository evalRepo = mock(MonitoringEvaluationRepository.class);
    private final JsonUtil jsonUtil = mock(JsonUtil.class);

    private final UserContext userContext = new UserContext();
    private final OwnershipGuard guard = new OwnershipGuard(userContext);

    private final ActiveTradesService service =
            new ActiveTradesService(reader, evalRepo, jsonUtil, guard);

    private final UUID alice = UUID.randomUUID();

    private void loginAs(UUID id, boolean admin) {
        userContext.set(new AuthenticatedUser(id, "LIVE", admin, "UPSTOX"));
    }

    @AfterEach
    void clear() { userContext.clear(); }

    @Test
    void normalUser_scopesToOwnId() {
        loginAs(alice, false);
        when(reader.findActiveForUser(alice)).thenReturn(List.of());

        service.findActiveForCurrentUser();

        verify(reader).findActiveForUser(alice);
    }

    @Test
    void admin_scopeIsNull() {
        loginAs(alice, true);
        when(reader.findActiveForUser(null)).thenReturn(List.of());

        service.findActiveForCurrentUser();

        verify(reader).findActiveForUser(isNull());
    }

    @Test
    void anonymous_throws401() {
        assertThatThrownBy(service::findActiveForCurrentUser)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
