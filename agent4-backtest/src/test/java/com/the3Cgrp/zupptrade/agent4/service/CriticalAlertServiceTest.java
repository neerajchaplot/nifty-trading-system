package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.CriticalAlertDto;
import com.the3Cgrp.zupptrade.agent4.exception.AlertNotFoundException;
import com.the3Cgrp.zupptrade.agent4.repository.CriticalAlertRepository;
import com.the3Cgrp.zupptrade.agent4.repository.CriticalAlertRepository.AlertOwnership;
import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CriticalAlertServiceTest {

    private final CriticalAlertRepository repo = mock(CriticalAlertRepository.class);
    private final UserContext userContext = new UserContext();
    private final OwnershipGuard guard = new OwnershipGuard(userContext);
    private final CriticalAlertService service = new CriticalAlertService(repo, guard);

    private final UUID alice = UUID.randomUUID();
    private final UUID bob   = UUID.randomUUID();

    private void loginAs(UUID profileId, boolean admin) {
        userContext.set(new AuthenticatedUser(profileId, "LIVE", admin, "UPSTOX"));
    }

    @AfterEach
    void clearIdentity() {
        userContext.clear();
    }

    // ── getLiveAlerts (scoped) ──────────────────────────────────────────────────

    @Test
    void getLiveAlerts_scopesToCallerAndMapsTradeDetailsJsonToNestedObject() {
        loginAs(alice, false);
        UUID alertId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("alert_id", alertId);
        row.put("trade_id", tradeId);
        row.put("alert_reason", "ambiguous placement failure — check Upstox positions");
        row.put("trade_details", "{\"tag\":\"ZUPP_ABCD1234\",\"outcome\":\"RECONCILE_REQUIRED\"}");
        row.put("status", "LIVE");
        row.put("created_at", Timestamp.valueOf(LocalDateTime.of(2026, 7, 30, 10, 15)));
        row.put("acknowledged_at", null);
        when(repo.findLive(alice)).thenReturn(List.of(row));

        List<CriticalAlertDto> alerts = service.getLiveAlerts();

        assertThat(alerts).hasSize(1);
        CriticalAlertDto dto = alerts.get(0);
        assertThat(dto.alertId()).isEqualTo(alertId);
        assertThat(dto.tradeId()).isEqualTo(tradeId);
        assertThat(dto.status()).isEqualTo("LIVE");
        assertThat(dto.acknowledgedAt()).isNull();
        assertThat(dto.tradeDetails())
                .containsEntry("tag", "ZUPP_ABCD1234")
                .containsEntry("outcome", "RECONCILE_REQUIRED");
        verify(repo).findLive(alice);   // scoped to the caller
    }

    @Test
    void getLiveAlerts_admin_scopeIsNull() {
        loginAs(alice, true);
        when(repo.findLive(null)).thenReturn(List.of());

        service.getLiveAlerts();

        verify(repo).findLive(null);    // admin → unscoped
    }

    @Test
    void getLiveAlerts_malformedTradeDetails_exposesRawWithoutFailing() {
        loginAs(alice, false);
        Map<String, Object> row = new HashMap<>();
        row.put("alert_id", UUID.randomUUID());
        row.put("trade_id", null);
        row.put("alert_reason", "reason");
        row.put("trade_details", "not-json");
        row.put("status", "LIVE");
        row.put("created_at", Timestamp.valueOf(LocalDateTime.now()));
        row.put("acknowledged_at", null);
        when(repo.findLive(alice)).thenReturn(List.of(row));

        List<CriticalAlertDto> alerts = service.getLiveAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).tradeDetails()).containsEntry("raw", "not-json");
        assertThat(alerts.get(0).tradeId()).isNull();
    }

    @Test
    void getLiveAlerts_anonymous_throws401() {
        assertThatThrownBy(() -> service.getLiveAlerts())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── acknowledge (owner-guarded) ─────────────────────────────────────────────

    @Test
    void acknowledge_ownerAndRowUpdated_succeeds() {
        loginAs(alice, false);
        UUID alertId = UUID.randomUUID();
        when(repo.findAlertOwner(alertId)).thenReturn(Optional.of(new AlertOwnership(alice)));
        when(repo.acknowledge(alertId)).thenReturn(1);

        service.acknowledge(alertId); // no throw
        verify(repo).acknowledge(alertId);
    }

    @Test
    void acknowledge_ownerButAlreadyAcknowledged_throwsAlertNotFound() {
        loginAs(alice, false);
        UUID alertId = UUID.randomUUID();
        when(repo.findAlertOwner(alertId)).thenReturn(Optional.of(new AlertOwnership(alice)));
        when(repo.acknowledge(any(UUID.class))).thenReturn(0);

        assertThatThrownBy(() -> service.acknowledge(alertId))
                .isInstanceOf(AlertNotFoundException.class)
                .hasMessageContaining(alertId.toString());
    }

    @Test
    void acknowledge_absentAlert_throwsAlertNotFound_beforeUpdate() {
        loginAs(alice, false);
        UUID alertId = UUID.randomUUID();
        when(repo.findAlertOwner(alertId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(alertId))
                .isInstanceOf(AlertNotFoundException.class);
        verify(repo, never()).acknowledge(any());
    }

    @Test
    void acknowledge_anotherUsersAlert_throws403_neverUpdates() {
        loginAs(bob, false);
        UUID alertId = UUID.randomUUID();
        when(repo.findAlertOwner(alertId)).thenReturn(Optional.of(new AlertOwnership(alice)));

        assertThatThrownBy(() -> service.acknowledge(alertId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(repo, never()).acknowledge(any());
    }

    @Test
    void acknowledge_admin_canAcknowledgeAnyAlert() {
        loginAs(bob, true);   // admin, not the owner
        UUID alertId = UUID.randomUUID();
        when(repo.findAlertOwner(alertId)).thenReturn(Optional.of(new AlertOwnership(alice)));
        when(repo.acknowledge(alertId)).thenReturn(1);

        service.acknowledge(alertId); // no throw
        verify(repo).acknowledge(alertId);
    }
}
