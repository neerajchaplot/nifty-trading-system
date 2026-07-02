package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.client.Agent1ScoreClient;
import com.the3Cgrp.zupptrade.agent3.client.Agent2RecommendClient;
import com.the3Cgrp.zupptrade.agent3.client.Agent5ExecuteClient;
import com.the3Cgrp.zupptrade.agent3.client.Agent5ExitClient;
import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.dto.EvaluationResponse;
import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.ledger.LedgerEventType;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import com.the3Cgrp.zupptrade.shared.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReadjustmentService — all external dependencies mocked.
 *
 * Covers every branching outcome in the 6-step readjust flow:
 *   1. DTE guard blocks re-entry near expiry
 *   2. Exit failure aborts without re-entry
 *   3. Agent 1 failure after successful exit
 *   4. Agent 2 no valid re-entry (gates fail)
 *   5. Agent 2 confirm failure
 *   6. Agent 5 execute failure
 *   7. Full happy path — new trade ACTIVE
 *   8. VIX stress boundary — correct relaxed PoP selected
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadjustmentServiceTest {

    @Mock private Agent5ExitClient       agent5ExitClient;
    @Mock private Agent1ScoreClient      agent1ScoreClient;
    @Mock private Agent2RecommendClient  agent2RecommendClient;
    @Mock private Agent5ExecuteClient    agent5ExecuteClient;
    @Mock private MonitoringProperties   props;
    @Mock private AlertService           alertService;
    @Mock private JdbcTemplate           jdbc;
    @Mock private TradeLedgerService     ledger;

    private ReadjustmentService service;

    private static final UUID OLD_TRADE_ID    = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");
    private static final UUID USER_PROFILE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000000");
    private static final UUID SIGNAL_ID       = UUID.fromString("cccccccc-0000-0000-0000-000000000000");
    private static final UUID NEW_TRADE_ID    = UUID.fromString("dddddddd-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        service = new ReadjustmentService(
                agent5ExitClient, agent1ScoreClient, agent2RecommendClient,
                agent5ExecuteClient, props, alertService, jdbc, ledger);

        // Default config values used in all tests
        when(props.getReadjustMinDteDays()).thenReturn(1);
        when(props.getReadjustVixStressThreshold()).thenReturn(new BigDecimal("22"));
        when(props.getReadjustPopNormalVix()).thenReturn(new BigDecimal("65.0"));
        when(props.getReadjustPopStressedVix()).thenReturn(new BigDecimal("70.0"));
    }

    // ── 1. DTE guard — blocks re-entry on expiry day ──────────────────────────

    @Test
    void handle_dteBelowMinimum_exitsOnlyNoReentry() {
        // expiryDate = today → DTE=0 < minDteDays=1 → exit only
        TradeMonitorData trade = tradeMock(LocalDate.now());
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("20.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(true);

        service.handle(trade, config, response);

        // Alert about DTE block
        verify(alertService).critical(eq(OLD_TRADE_ID), eq("readjust_dte_blocked"), anyString());
        // Exit is still triggered
        verify(agent5ExitClient).exitTrade(any(), any(), any(), any(), any(), any(), anyInt());
        // No Agent1/Agent2/Agent5-execute calls
        verifyNoInteractions(agent1ScoreClient, agent2RecommendClient, agent5ExecuteClient);
    }

    @Test
    void handle_dteAtMinimum_proceedsWithReentry() {
        // DTE=1 exactly at minimum — re-entry should proceed
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(1));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(true);

        service.handle(trade, config, response);

        verify(agent1ScoreClient).score(any());
        verify(agent2RecommendClient).recommend(any(), any(), any());
    }

    // ── 2. DB status update fails — abort before exit call ───────────────────

    @Test
    void handle_jdbcUpdateFails_abortsCompletely() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(0); // no rows — concurrent update

        service.handle(trade, config, response);

        // Nothing should proceed after failed status update
        verifyNoInteractions(agent5ExitClient, agent1ScoreClient, agent2RecommendClient, agent5ExecuteClient);
    }

    @Test
    void handle_jdbcUpdateThrows_alertsAndAborts() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenThrow(new RuntimeException("DB timeout"));

        service.handle(trade, config, response);

        verify(alertService).critical(eq(OLD_TRADE_ID), eq("readjust_exit_status_failed"), anyString());
        verifyNoInteractions(agent5ExitClient, agent1ScoreClient, agent2RecommendClient, agent5ExecuteClient);
    }

    // ── 3. Agent 5 exit fails ────────────────────────────────────────────────

    @Test
    void handle_exitFails_abortsReentry() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(false); // exit failed

        service.handle(trade, config, response);

        verify(alertService).critical(eq(OLD_TRADE_ID), eq("readjust_exit_failed"), anyString());
        verifyNoInteractions(agent1ScoreClient, agent2RecommendClient, agent5ExecuteClient);
    }

    // ── 4. Agent 1 fails after successful exit ───────────────────────────────

    @Test
    void handle_agent1Fails_alertsAndStopsAfterExit() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.empty()); // Agent 1 down

        service.handle(trade, config, response);

        verify(alertService).critical(eq(OLD_TRADE_ID), eq("readjust_agent1_failed"), anyString());
        verifyNoInteractions(agent2RecommendClient, agent5ExecuteClient);
    }

    // ── 5. Agent 2 recommend — no valid re-entry ────────────────────────────

    @Test
    void handle_agent2GatesFail_warnsNoReentry() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.empty()); // gates failed

        service.handle(trade, config, response);

        verify(alertService).warning(eq(OLD_TRADE_ID), eq("readjust_no_reentry"), anyString());
        verifyNoInteractions(agent5ExecuteClient);
    }

    // ── 6. Agent 2 confirm fails ──────────────────────────────────────────────

    @Test
    void handle_confirmFails_criticalAlert() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.empty());

        service.handle(trade, config, response);

        verify(alertService).critical(eq(OLD_TRADE_ID), eq("readjust_confirm_failed"), anyString());
        verifyNoInteractions(agent5ExecuteClient);
    }

    // ── 7. Agent 5 execute fails ─────────────────────────────────────────────

    @Test
    void handle_executeFails_criticalAlert() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(false);

        service.handle(trade, config, response);

        verify(alertService).critical(eq(OLD_TRADE_ID), eq("readjust_execute_failed"), anyString());
    }

    // ── 8. Full happy path ────────────────────────────────────────────────────

    @Test
    void handle_happyPath_completesAllStepsAndAlertsSuccess() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0")); // VIX < 22 → normal gate

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(true);

        service.handle(trade, config, response);

        // Verify success alert
        verify(alertService).info(eq(OLD_TRADE_ID), eq("readjust_success"), anyString());
        // Verify ledger audit entry
        verify(ledger).record(eq(OLD_TRADE_ID), eq(LedgerEventType.TRADE_CLOSE_INITIATED), any(), anyString());
        // Verify no error alerts
        verify(alertService, never()).critical(any(), any(), any());
    }

    @Test
    void handle_happyPath_signalIdPassedToAgent2() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(true);

        service.handle(trade, config, response);

        // Agent 2 must receive the exact signalId returned by Agent 1
        verify(agent2RecommendClient).recommend(eq(USER_PROFILE_ID), eq(SIGNAL_ID), any());
    }

    // ── 9. VIX stress boundary — relaxed PoP selection ────────────────────────

    @Test
    void handle_vixAbove22_usesStressedPop70() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("23.5")); // VIX > 22

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(true);

        service.handle(trade, config, response);

        ArgumentCaptor<BigDecimal> popCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(agent2RecommendClient).recommend(any(), any(), popCaptor.capture());
        assertThat(popCaptor.getValue()).isEqualByComparingTo(new BigDecimal("70.0")); // stressed gate
    }

    @Test
    void handle_vixBelow22_usesNormalPop65() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("19.0")); // VIX < 22

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(true);

        service.handle(trade, config, response);

        ArgumentCaptor<BigDecimal> popCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(agent2RecommendClient).recommend(any(), any(), popCaptor.capture());
        assertThat(popCaptor.getValue()).isEqualByComparingTo(new BigDecimal("65.0")); // normal gate
    }

    @Test
    void handle_vixNull_defaultsToNormalGate() {
        // If VIX is unavailable (null), fall back to normal 65% gate — never crash
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponseWithVix(null);

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(true);

        service.handle(trade, config, response);

        ArgumentCaptor<BigDecimal> popCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(agent2RecommendClient).recommend(any(), any(), popCaptor.capture());
        assertThat(popCaptor.getValue()).isEqualByComparingTo(new BigDecimal("65.0")); // normal gate
    }

    // ── 10. userProfileId null ────────────────────────────────────────────────

    @Test
    void handle_userProfileIdNull_alertsAndStopsAfterExit() {
        // TradeMonitorData with null userProfileId — means DB inconsistency
        TradeMonitorData trade = new TradeMonitorData(
                OLD_TRADE_ID, null /* no profile */, TradeStatus.ACTIVE,
                null, null, "TRD-001", LocalDate.now().plusDays(5), null);
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));

        service.handle(trade, config, response);

        verify(alertService).critical(eq(OLD_TRADE_ID), eq("readjust_no_profile"), anyString());
        verifyNoInteractions(agent2RecommendClient, agent5ExecuteClient);
    }

    // ── IC readjustment — 4-leg exit routing ─────────────────────────────────

    @Test
    void handle_ironCondor_exitRoutesToIcExitMethod() {
        // When config.shortLeg2() != null (IC), exitOldTrade must call exitIronCondorTrade, not exitTrade
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = icConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitIronCondorTrade(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(true);

        service.handle(trade, config, response);

        // 4-leg IC exit must be called, 2-leg must NOT be called
        verify(agent5ExitClient).exitIronCondorTrade(
                eq(OLD_TRADE_ID), anyString(),
                eq("NFO_OPT|NIFTY|2026-06-24|23500|PE"), eq(LegAction.SELL),
                eq("NFO_OPT|NIFTY|2026-06-24|23400|PE"), eq(LegAction.BUY),
                eq("NFO_OPT|NIFTY|2026-06-24|24000|CE"), eq(LegAction.SELL),
                eq("NFO_OPT|NIFTY|2026-06-24|24100|CE"), eq(LegAction.BUY),
                anyInt());
        verify(agent5ExitClient, never()).exitTrade(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void handle_ironCondor_exitFails_alertsAndAbortsReentry() {
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = icConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitIronCondorTrade(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(false); // IC exit failed

        service.handle(trade, config, response);

        verify(alertService).critical(eq(OLD_TRADE_ID), eq("readjust_exit_failed"), anyString());
        verifyNoInteractions(agent1ScoreClient, agent2RecommendClient, agent5ExecuteClient);
    }

    @Test
    void handle_ironCondor_dteGuard_callsIcExitNotTwoLeg() {
        // Even when DTE guard blocks re-entry, exit must still use 4-leg path for IC
        TradeMonitorData trade = tradeMock(LocalDate.now()); // DTE=0 < minDteDays=1
        MonitorConfigDto config = icConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitIronCondorTrade(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(true);

        service.handle(trade, config, response);

        verify(agent5ExitClient).exitIronCondorTrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
        verify(agent5ExitClient, never()).exitTrade(any(), any(), any(), any(), any(), any(), anyInt());
        // DTE guard fires — no re-entry
        verifyNoInteractions(agent1ScoreClient, agent2RecommendClient, agent5ExecuteClient);
    }

    @Test
    void handle_creditSpread_exitRoutesToTwoLegMethod() {
        // Confirm 2-leg (Bull Put Spread) still uses exitTrade — not IC path
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = creditConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitTrade(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(true);

        service.handle(trade, config, response);

        verify(agent5ExitClient).exitTrade(any(), any(), any(), any(), any(), any(), anyInt());
        verify(agent5ExitClient, never()).exitIronCondorTrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void handle_ironCondor_reentry_executesIcCardWith4Legs() {
        // When Agent 2 re-recommends an IC, the execute card must have shortLeg2+longLeg2 populated
        TradeMonitorData trade = tradeMock(LocalDate.now().plusDays(5));
        MonitorConfigDto config = icConfig();
        EvaluationResponse response = evaluationResponse(new BigDecimal("18.0"));

        when(jdbc.update(anyString(), any(UUID.class))).thenReturn(1);
        when(agent5ExitClient.exitIronCondorTrade(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(true);
        when(agent1ScoreClient.score(any())).thenReturn(Optional.of(SIGNAL_ID));
        when(agent2RecommendClient.recommend(any(), any(), any())).thenReturn(Optional.of(pendingIcTradeCard()));
        when(agent2RecommendClient.confirm(any())).thenReturn(Optional.of(confirmedIcTradeCard()));
        when(agent5ExecuteClient.execute(any())).thenReturn(true);

        service.handle(trade, config, response);

        ArgumentCaptor<TradeCardDto> captor = ArgumentCaptor.forClass(TradeCardDto.class);
        verify(agent5ExecuteClient).execute(captor.capture());
        TradeCardDto executedCard = captor.getValue();

        // All 4 IC legs must be present in the card sent to Agent 5
        assertThat(executedCard.shortLeg()).isNotNull();
        assertThat(executedCard.longLeg()).isNotNull();
        assertThat(executedCard.shortLeg2()).isNotNull();
        assertThat(executedCard.longLeg2()).isNotNull();
        assertThat(executedCard.shortLeg().optionType()).isEqualTo(OptionType.PE);
        assertThat(executedCard.shortLeg2().optionType()).isEqualTo(OptionType.CE);
        assertThat(executedCard.strategy()).isEqualTo(Strategy.IRON_CONDOR);
    }

    // ── Test data builders ────────────────────────────────────────────────────

    private TradeMonitorData tradeMock(LocalDate expiryDate) {
        return new TradeMonitorData(
                OLD_TRADE_ID, USER_PROFILE_ID, TradeStatus.ACTIVE,
                null, null, "TRD-TEST", expiryDate, null);
    }

    private MonitorConfigDto creditConfig() {
        TradeLegDto shortLegDto = new TradeLegDto(OptionType.PE, 23500,
                new BigDecimal("45.50"), LegAction.SELL, new BigDecimal("-0.18"),
                new BigDecimal("0.82"), "NFO_OPT|NIFTY|2026-06-17|23500|PE");
        TradeLegDto longLegDto = new TradeLegDto(OptionType.PE, 23400,
                new BigDecimal("28.20"), LegAction.BUY, new BigDecimal("-0.14"),
                new BigDecimal("0.88"), "NFO_OPT|NIFTY|2026-06-17|23400|PE");

        MonitorThresholdsDto thresholds = MonitorThresholdsDto.twoLeg(
                new BigDecimal("23650"),   // t1WatchNiftyLevel
                new BigDecimal("23575"),   // t2ReadjustNiftyLevel
                new BigDecimal("23500"),   // t3ExitNiftyLevel
                new BigDecimal("-9750"),   // t2LossThreshold
                new BigDecimal("-19500")); // t3LossThreshold

        return MonitorConfigDto.twoLeg(
                OLD_TRADE_ID, Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT,
                shortLegDto, longLegDto,
                new BigDecimal("17.30"),  // actualNetPremiumPerUnit
                6, 65,
                new BigDecimal("6747.00"),  // maxProfitTotal
                new BigDecimal("39000.00"), // actualMaxLossTotal
                false, null,                // slippageAlert, slippageAmount
                thresholds,
                LocalDate.now().plusDays(5), 5);
    }

    private MonitorConfigDto icConfig() {
        TradeLegDto peShort = new TradeLegDto(OptionType.PE, 23500,
                new BigDecimal("40.00"), LegAction.SELL, new BigDecimal("-0.18"),
                new BigDecimal("0.82"), "NFO_OPT|NIFTY|2026-06-24|23500|PE");
        TradeLegDto peLong  = new TradeLegDto(OptionType.PE, 23400,
                new BigDecimal("25.00"), LegAction.BUY, new BigDecimal("-0.13"),
                new BigDecimal("0.87"), "NFO_OPT|NIFTY|2026-06-24|23400|PE");
        TradeLegDto ceShort = new TradeLegDto(OptionType.CE, 24000,
                new BigDecimal("30.00"), LegAction.SELL, new BigDecimal("0.16"),
                new BigDecimal("0.84"), "NFO_OPT|NIFTY|2026-06-24|24000|CE");
        TradeLegDto ceLong  = new TradeLegDto(OptionType.CE, 24100,
                new BigDecimal("18.00"), LegAction.BUY, new BigDecimal("0.12"),
                new BigDecimal("0.88"), "NFO_OPT|NIFTY|2026-06-24|24100|CE");

        MonitorThresholdsDto thresholds = MonitorThresholdsDto.ironCondor(
                new BigDecimal("23600"), new BigDecimal("23550"), new BigDecimal("23500"),
                new BigDecimal("23900"), new BigDecimal("23950"), new BigDecimal("24000"),
                new BigDecimal("15000"), new BigDecimal("30000"));

        return MonitorConfigDto.ironCondor(
                OLD_TRADE_ID, Strategy.IRON_CONDOR, SpreadDirection.CREDIT,
                peShort, peLong, ceShort, ceLong,
                new BigDecimal("27.00"), 6, 65,
                new BigDecimal("10530"), new BigDecimal("39000"),
                false, null, thresholds,
                LocalDate.now().plusDays(5), 5);
    }

    private TradeCardDto pendingIcTradeCard() {
        TradeLegDto peShort = new TradeLegDto(OptionType.PE, 23350,
                new BigDecimal("38.00"), LegAction.SELL, new BigDecimal("-0.17"),
                new BigDecimal("0.83"), "NFO_OPT|NIFTY|2026-06-24|23350|PE");
        TradeLegDto peLong = new TradeLegDto(OptionType.PE, 23250,
                new BigDecimal("24.00"), LegAction.BUY, new BigDecimal("-0.13"),
                new BigDecimal("0.87"), "NFO_OPT|NIFTY|2026-06-24|23250|PE");
        TradeLegDto ceShort = new TradeLegDto(OptionType.CE, 24050,
                new BigDecimal("28.00"), LegAction.SELL, new BigDecimal("0.15"),
                new BigDecimal("0.85"), "NFO_OPT|NIFTY|2026-06-24|24050|CE");
        TradeLegDto ceLong = new TradeLegDto(OptionType.CE, 24150,
                new BigDecimal("17.00"), LegAction.BUY, new BigDecimal("0.11"),
                new BigDecimal("0.89"), "NFO_OPT|NIFTY|2026-06-24|24150|CE");

        return new TradeCardDto(
                NEW_TRADE_ID, Strategy.IRON_CONDOR, SpreadDirection.CREDIT,
                LocalDate.now().plusDays(5), 5,
                peShort, peLong,
                ceShort, ceLong,                                 // shortLeg2, longLeg2
                new BigDecimal("25.00"), 6, 65,
                new BigDecimal("9750.00"), new BigDecimal("46800.00"), new BigDecimal("23400.00"),
                new BigDecimal("82.00"), new BigDecimal("84.00"), new BigDecimal("2.00"),
                new BigDecimal("1.07"), new BigDecimal("130.00"), new BigDecimal("-0.02"),
                null, null, "Iron condor readjust re-entry — neutral bias",
                null, null, TradeStatus.PENDING_CONFIRM
        );
    }

    private TradeCardDto confirmedIcTradeCard() {
        TradeCardDto card = pendingIcTradeCard();
        return new TradeCardDto(
                card.tradeId(), card.strategy(), card.spreadDirection(), card.expiryDate(), card.dte(),
                card.shortLeg(), card.longLeg(), card.shortLeg2(), card.longLeg2(),
                card.netPremiumPerUnit(), card.lots(), card.lotSize(),
                card.maxProfitTotal(), card.theoreticalMaxLossTotal(), card.realExpectedLossTotal(),
                card.pop(), card.popp(), card.popGap(), card.roc(), card.rocAnnualised(), card.netDelta(),
                card.gateResults(), card.thresholds(), card.rationale(),
                card.generatedAt(), card.validUntil(), TradeStatus.CONFIRMED
        );
    }

    private EvaluationResponse evaluationResponse(BigDecimal vix) {
        return evaluationResponseWithVix(vix);
    }

    private EvaluationResponse evaluationResponseWithVix(BigDecimal vix) {
        return new EvaluationResponse(
                UUID.randomUUID(), OLD_TRADE_ID,
                MonitorAction.READJUST, ThresholdHit.T2_READJUST_NIFTY,
                "Spot within T2 threshold of short strike",
                new BigDecimal("23575"), vix, new BigDecimal("0.68"),
                new BigDecimal("-9750"), new BigDecimal("17.30"),
                new BigDecimal("61.20"), new BigDecimal("40.80"),
                Instant.now());
    }

    private TradeCardDto pendingTradeCard() {
        TradeLegDto shortLeg = new TradeLegDto(OptionType.PE, 23350,
                new BigDecimal("38.50"), LegAction.SELL, new BigDecimal("-0.17"),
                new BigDecimal("0.68"), "NFO_OPT|NIFTY|2026-06-17|23350|PE");
        TradeLegDto longLeg = new TradeLegDto(OptionType.PE, 23250,
                new BigDecimal("24.10"), LegAction.BUY, new BigDecimal("-0.13"),
                new BigDecimal("0.75"), "NFO_OPT|NIFTY|2026-06-17|23250|PE");

        return new TradeCardDto(
                NEW_TRADE_ID, Strategy.BULL_PUT_SPREAD, SpreadDirection.CREDIT,
                LocalDate.now().plusDays(5), 5,
                shortLeg, longLeg,
                null, null,              // shortLeg2, longLeg2 — 2-leg spread
                new BigDecimal("14.40"), 6, 65,
                new BigDecimal("5616.00"), new BigDecimal("39000.00"), new BigDecimal("19500.00"),
                new BigDecimal("68.00"), new BigDecimal("75.00"), new BigDecimal("7.00"),
                new BigDecimal("0.89"), new BigDecimal("65.00"), new BigDecimal("-0.04"),
                null, null, "Readjust re-entry: lower strike spread",
                null, null, TradeStatus.PENDING_CONFIRM
        );
    }

    private TradeCardDto confirmedTradeCard() {
        TradeCardDto card = pendingTradeCard();
        return new TradeCardDto(
                card.tradeId(), card.strategy(), card.spreadDirection(), card.expiryDate(), card.dte(),
                card.shortLeg(), card.longLeg(), card.shortLeg2(), card.longLeg2(),
                card.netPremiumPerUnit(), card.lots(), card.lotSize(),
                card.maxProfitTotal(), card.theoreticalMaxLossTotal(), card.realExpectedLossTotal(),
                card.pop(), card.popp(), card.popGap(), card.roc(), card.rocAnnualised(), card.netDelta(),
                card.gateResults(), card.thresholds(), card.rationale(),
                card.generatedAt(), card.validUntil(), TradeStatus.CONFIRMED
        );
    }
}
