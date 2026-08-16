package com.the3Cgrp.zupptrade.agent5.controller;

import com.the3Cgrp.zupptrade.agent5.dto.LegOrderRequest;
import com.the3Cgrp.zupptrade.agent5.service.MarginCheckService;
import com.the3Cgrp.zupptrade.agent5.service.TradeExecutionService;
import com.the3Cgrp.zupptrade.agent5.service.UpstoxConnectionCheckService;
import com.the3Cgrp.zupptrade.agent5.dto.ExecuteTradeRequest;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure-unit test of the agent5 ownership guard on /execute (no Spring context, no DB).
 * Owner → allowed; another user → 403; anonymous → 401 — all driven by OwnershipGuard.
 */
class ExecutionControllerGuardTest {

    private final TradeExecutionService        exec   = mock(TradeExecutionService.class);
    private final UpstoxConnectionCheckService conn   = mock(UpstoxConnectionCheckService.class);
    private final MarginCheckService           margin = mock(MarginCheckService.class);
    private final JdbcTemplate                 jdbc   = mock(JdbcTemplate.class);
    private final OwnershipGuard               guard  = mock(OwnershipGuard.class);

    private final ExecutionController controller =
            new ExecutionController(exec, conn, margin, jdbc, guard);

    private final UUID tradeId = UUID.randomUUID();
    private final UUID owner   = UUID.randomUUID();

    private ExecuteTradeRequest request() {
        return new ExecuteTradeRequest(tradeId, List.of(
                new LegOrderRequest("NFO_OPT|NIFTY|2026-06-09|24500|PE",
                        OptionType.PE, 24500, LegAction.SELL, new BigDecimal("50.00"), 75)));
    }

    @SuppressWarnings("unchecked")
    private void stubOwnerLookup() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(tradeId))).thenReturn(List.of(owner));
    }

    @Test
    void owner_isAllowed() {
        stubOwnerLookup(); // guard.requireOwner does nothing (default mock) → proceeds

        controller.execute(request(), null); // exec.execute returns null (unstubbed) → ok(null)

        verify(guard).requireOwner(owner);
        verify(exec).execute(any(), any());
    }

    @Test
    void anotherUsersTrade_isForbidden() {
        stubOwnerLookup();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(guard).requireOwner(owner);

        assertThatThrownBy(() -> controller.execute(request(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(exec, never()).execute(any(), any());
    }

    @Test
    void anonymous_isUnauthorized() {
        stubOwnerLookup();
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED)).when(guard).requireOwner(owner);

        assertThatThrownBy(() -> controller.execute(request(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(exec, never()).execute(any(), any());
    }
}
