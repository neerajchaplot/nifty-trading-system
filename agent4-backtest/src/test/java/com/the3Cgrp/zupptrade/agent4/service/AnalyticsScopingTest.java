package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.mapper.TradeAuditMapper;
import com.the3Cgrp.zupptrade.agent4.exception.TradeNotFoundException;
import com.the3Cgrp.zupptrade.agent4.repository.AnalyticsTradeRepository;
import com.the3Cgrp.zupptrade.agent4.repository.MonitoringEvaluationRepository;
import com.the3Cgrp.zupptrade.agent4.repository.TradeExecutionRepository;
import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 read-isolation wiring for agent4 services: the correct scope reaches the repository
 * (caller id for a user, null for an admin), anonymous callers are rejected, and by-id audit is
 * owner-guarded. Actual row filtering lives in the SQL view and is covered by AnalyticsControllerIT.
 */
class AnalyticsScopingTest {

    private final AnalyticsTradeRepository tradeRepo = mock(AnalyticsTradeRepository.class);
    private final MonitoringEvaluationRepository evalRepo = mock(MonitoringEvaluationRepository.class);
    private final TradeExecutionRepository execRepo = mock(TradeExecutionRepository.class);
    private final TradeAuditMapper auditMapper = mock(TradeAuditMapper.class);

    private final UserContext userContext = new UserContext();
    private final OwnershipGuard guard = new OwnershipGuard(userContext);

    private final TradeListService listService = new TradeListService(tradeRepo, guard);
    private final TradeAuditService auditService =
            new TradeAuditService(tradeRepo, evalRepo, execRepo, auditMapper, guard);

    private final UUID alice = UUID.randomUUID();
    private final UUID bob   = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 1, 1);
    private final LocalDate to   = LocalDate.of(2026, 12, 31);

    private void loginAs(UUID id, boolean admin) {
        userContext.set(new AuthenticatedUser(id, "LIVE", admin, "UPSTOX"));
    }

    @AfterEach
    void clear() { userContext.clear(); }

    private void stubEmptyList() {
        lenient().when(tradeRepo.findClosedTrades(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        lenient().when(tradeRepo.countClosedTrades(any(), any(), any())).thenReturn(0L);
        lenient().when(tradeRepo.findCorruptedTrades(any(), any(), any())).thenReturn(List.of());
    }

    // ── TradeListService: scope threading ───────────────────────────────────────

    @Test
    void tradeList_normalUser_scopesToOwnId() {
        loginAs(alice, false);
        stubEmptyList();

        listService.getTrades(from, to, 0, 5);

        verify(tradeRepo).findClosedTrades(eq(from), eq(to), eq(alice), eq(0), eq(5));
        verify(tradeRepo).countClosedTrades(from, to, alice);
    }

    @Test
    void tradeList_admin_scopeIsNull() {
        loginAs(alice, true);
        stubEmptyList();

        listService.getTrades(from, to, 0, 5);

        verify(tradeRepo).findClosedTrades(eq(from), eq(to), isNull(), eq(0), eq(5));
    }

    @Test
    void tradeList_anonymous_throws401() {
        assertThatThrownBy(() -> listService.getTrades(from, to, 0, 5))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── TradeAuditService: by-id ownership ──────────────────────────────────────

    private Map<String, Object> tradeRowOwnedBy(UUID owner) {
        Map<String, Object> row = new HashMap<>();
        row.put("user_profile_id", owner);
        return row;
    }

    @Test
    void audit_owner_succeeds() {
        loginAs(alice, false);
        UUID tradeId = UUID.randomUUID();
        when(tradeRepo.findClosedTradeById(tradeId)).thenReturn(java.util.Optional.of(tradeRowOwnedBy(alice)));
        when(evalRepo.findByTradeId(tradeId)).thenReturn(List.of());
        when(execRepo.findByTradeId(tradeId)).thenReturn(List.of());

        assertThatCode(() -> auditService.getAudit(tradeId)).doesNotThrowAnyException();
    }

    @Test
    void audit_crossUser_throws403_beforeLoadingChapters() {
        loginAs(bob, false);
        UUID tradeId = UUID.randomUUID();
        when(tradeRepo.findClosedTradeById(tradeId)).thenReturn(java.util.Optional.of(tradeRowOwnedBy(alice)));

        assertThatThrownBy(() -> auditService.getAudit(tradeId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(evalRepo, never()).findByTradeId(any());
        verify(execRepo, never()).findByTradeId(any());
    }

    @Test
    void audit_admin_seesAnyTrade() {
        loginAs(bob, true);
        UUID tradeId = UUID.randomUUID();
        when(tradeRepo.findClosedTradeById(tradeId)).thenReturn(java.util.Optional.of(tradeRowOwnedBy(alice)));
        when(evalRepo.findByTradeId(tradeId)).thenReturn(List.of());
        when(execRepo.findByTradeId(tradeId)).thenReturn(List.of());

        assertThatCode(() -> auditService.getAudit(tradeId)).doesNotThrowAnyException();
    }

    @Test
    void audit_absentTrade_throws404() {
        loginAs(alice, false);
        UUID tradeId = UUID.randomUUID();
        when(tradeRepo.findClosedTradeById(tradeId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> auditService.getAudit(tradeId))
                .isInstanceOf(TradeNotFoundException.class);
    }

    @Test
    void audit_anonymous_throws401() {
        UUID tradeId = UUID.randomUUID();
        when(tradeRepo.findClosedTradeById(tradeId)).thenReturn(java.util.Optional.of(tradeRowOwnedBy(alice)));

        assertThatThrownBy(() -> auditService.getAudit(tradeId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
