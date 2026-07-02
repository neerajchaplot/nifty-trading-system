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
import com.the3Cgrp.zupptrade.ledger.payload.TradeCloseInitiatedPayload;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Automated readjustment flow — T2 READJUST trigger handler.
 *
 * When Agent 3's monitoring engine signals READJUST (short strike under pressure),
 * this service executes a fully automated re-entry without user confirmation:
 *
 *   1. DTE guard — skip re-entry if DTE < readjustMinDteDays (expiry day / day-before).
 *                  Exit the current position but do NOT re-enter.
 *   2. Exit current position via Agent 5.
 *   3. Call Agent 1 /score for a fresh market signal.
 *   4. Call Agent 2 /recommend with relaxed G1 PoP gate:
 *        VIX ≤ 22 → popFloor = 65%
 *        VIX > 22 → popFloor = 70%
 *   5. Auto-confirm the recommendation (Agent 2 /confirm, no user input).
 *   6. Execute the new trade via Agent 5 /execute.
 *
 * Each step is guarded: failure alerts the user and aborts the remaining steps.
 * The old trade's status in the DB follows the normal exit path:
 *   ACTIVE → EXIT_IN_PROGRESS (set here) → CLOSED (set by Agent 5 on successful exit).
 *
 * Design: Strategy / Template pattern hybrid — fixed sequence of steps, each
 * independently fallible and logged, with early returns on failure.
 */
@Service
public class ReadjustmentService {

    private static final Logger log = LoggerFactory.getLogger(ReadjustmentService.class);

    private final Agent5ExitClient        agent5ExitClient;
    private final Agent1ScoreClient       agent1ScoreClient;
    private final Agent2RecommendClient   agent2RecommendClient;
    private final Agent5ExecuteClient     agent5ExecuteClient;
    private final MonitoringProperties    props;
    private final AlertService            alertService;
    private final JdbcTemplate            jdbc;
    private final TradeLedgerService      ledger;

    public ReadjustmentService(Agent5ExitClient agent5ExitClient,
                                Agent1ScoreClient agent1ScoreClient,
                                Agent2RecommendClient agent2RecommendClient,
                                Agent5ExecuteClient agent5ExecuteClient,
                                MonitoringProperties props,
                                AlertService alertService,
                                JdbcTemplate jdbc,
                                TradeLedgerService ledger) {
        this.agent5ExitClient      = agent5ExitClient;
        this.agent1ScoreClient     = agent1ScoreClient;
        this.agent2RecommendClient = agent2RecommendClient;
        this.agent5ExecuteClient   = agent5ExecuteClient;
        this.props                 = props;
        this.alertService          = alertService;
        this.jdbc                  = jdbc;
        this.ledger                = ledger;
    }

    /**
     * Handles the READJUST action for a single trade.
     *
     * Called by MonitorSchedulerService after the monitoring engine signals READJUST.
     * Never throws — all exceptions are caught and alerted.
     *
     * @param trade    the trade that hit T2 threshold
     * @param config   its monitor configuration (strikes, lots, instrument keys)
     * @param response the evaluation result carrying spot, VIX, and P&L context
     */
    public void handle(TradeMonitorData trade, MonitorConfigDto config, EvaluationResponse response) {
        UUID oldTradeId = trade.tradeId();
        log.warn("readjust.triggered tradeId={} tradeCode={} reason='{}' pnl={} vix={}",
                oldTradeId, trade.tradeCode(), response.reason(),
                response.markToMarketPnl(), response.vixLevel());

        // ── Step 1: DTE guard ──────────────────────────────────────────────────
        // On expiry day or day-before, no new position is safe to open mid-session.
        // We exit the old trade and stop here.
        int currentDte = calculateDte(trade.expiryDate());
        if (currentDte < props.getReadjustMinDteDays()) {
            log.warn("readjust.dte_guard_blocked tradeId={} dte={} minDte={}",
                    oldTradeId, currentDte, props.getReadjustMinDteDays());
            alertService.critical(oldTradeId, "readjust_dte_blocked",
                    "Trade " + trade.tradeCode() + " hit T2 READJUST but DTE=" + currentDte +
                    " is below minimum (" + props.getReadjustMinDteDays() + " day). " +
                    "Exiting only — no re-entry on near-expiry day. P&L: " + response.markToMarketPnl());
            exitOldTrade(trade, config, response.reason(), response.markToMarketPnl());
            return;
        }

        // ── Step 2: Exit current position ─────────────────────────────────────
        boolean exitSuccess = exitOldTrade(trade, config, response.reason(), response.markToMarketPnl());
        if (!exitSuccess) {
            // exitOldTrade already alerted — abort re-entry to avoid double exposure
            return;
        }

        // ── Step 3: Fresh Agent 1 signal ──────────────────────────────────────
        Optional<UUID> signalOpt = agent1ScoreClient.score(trade.expiryDate());
        if (signalOpt.isEmpty()) {
            alertService.critical(oldTradeId, "readjust_agent1_failed",
                    "Trade " + trade.tradeCode() + " exited successfully but Agent 1 signal fetch failed. " +
                    "Capital is flat. Manual re-entry required if conditions improve.");
            return;
        }
        UUID newSignalId = signalOpt.get();

        // ── Step 4: Agent 2 recommendation with relaxed G1 ─────────────────────
        // VIX from the evaluation response — already fetched this cycle, no extra call.
        BigDecimal relaxedPop = selectRelaxedPop(response.vixLevel());
        UUID userProfileId = trade.userProfileId();
        if (userProfileId == null) {
            alertService.critical(oldTradeId, "readjust_no_profile",
                    "Trade " + trade.tradeCode() + " exited but userProfileId is null in trades table. " +
                    "Cannot call Agent 2. Manual re-entry required.");
            return;
        }

        Optional<TradeCardDto> recommendOpt = agent2RecommendClient.recommend(
                userProfileId, newSignalId, relaxedPop);
        if (recommendOpt.isEmpty()) {
            alertService.warning(oldTradeId, "readjust_no_reentry",
                    "Trade " + trade.tradeCode() + " exited. Agent 2 found no valid re-entry " +
                    "(gates failed or strategy=SKIP at current market levels). " +
                    "Capital is flat. Manual re-entry at your discretion.");
            return;
        }
        TradeCardDto card = recommendOpt.get();

        // ── Step 5: Auto-confirm ───────────────────────────────────────────────
        Optional<TradeCardDto> confirmedOpt = agent2RecommendClient.confirm(card.tradeId());
        if (confirmedOpt.isEmpty()) {
            alertService.critical(oldTradeId, "readjust_confirm_failed",
                    "Trade " + trade.tradeCode() + " exited. New recommendation tradeId=" + card.tradeId() +
                    " could not be auto-confirmed. Manual confirmation and execution required.");
            return;
        }
        TradeCardDto confirmed = confirmedOpt.get();

        // ── Step 6: Execute new trade ──────────────────────────────────────────
        boolean executed = agent5ExecuteClient.execute(confirmed);
        if (!executed) {
            alertService.critical(oldTradeId, "readjust_execute_failed",
                    "Trade " + trade.tradeCode() + " readjusted. New trade " + confirmed.tradeId() +
                    " (" + confirmed.strategy() + ") is CONFIRMED but execution failed. " +
                    "Manual execution required — check Upstox margin and retry.");
        } else {
            log.info("readjust.complete oldTradeId={} newTradeId={} strategy={} pop={} lots={}",
                    oldTradeId, confirmed.tradeId(), confirmed.strategy(),
                    confirmed.pop(), confirmed.lots());
            alertService.info(oldTradeId, "readjust_success",
                    "Trade " + trade.tradeCode() + " readjusted. New trade " + confirmed.tradeId() +
                    " (" + confirmed.strategy() + ", " + confirmed.lots() + " lots, PoP=" + confirmed.pop() + "%) " +
                    "is now ACTIVE.");
        }
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    /**
     * Exits the old trade via Agent 5, with the same status-guard pattern used
     * by MonitorSchedulerService.triggerExit.
     *
     * @return true if exit was successfully triggered; false on any hard failure
     */
    private boolean exitOldTrade(TradeMonitorData trade, MonitorConfigDto config,
                                  String reason, BigDecimal pnl) {
        UUID tradeId = trade.tradeId();

        // Set EXIT_IN_PROGRESS to prevent duplicate exit attempts in the next cycle
        try {
            int updated = jdbc.update(
                    "UPDATE trades SET status = 'EXIT_IN_PROGRESS' WHERE id = ? AND status IN ('ACTIVE','EXIT_FAILED')",
                    tradeId);
            if (updated == 0) {
                log.warn("readjust.exit_skip tradeId={} — trade no longer ACTIVE/EXIT_FAILED (concurrent update?)",
                        tradeId);
                return false;
            }
        } catch (Exception e) {
            log.error("readjust.exit.status_update_failed tradeId={} error={}", tradeId, e.getMessage());
            alertService.critical(tradeId, "readjust_exit_status_failed",
                    "Trade " + trade.tradeCode() + " readjust aborted — could not set EXIT_IN_PROGRESS. " +
                    "DB error. MANUAL INTERVENTION REQUIRED.");
            return false;
        }

        // Audit: TRADE_CLOSE_INITIATED before calling Agent 5
        ledger.record(tradeId, LedgerEventType.TRADE_CLOSE_INITIATED,
                new TradeCloseInitiatedPayload("READJUST: " + reason, pnl, "AGENT3:READJUST"),
                "AGENT3:READJUST");

        boolean isIronCondor = config.shortLeg2() != null;

        boolean missingKey = config.shortLeg() == null || config.shortLeg().instrumentKey() == null ||
                             config.longLeg()  == null || config.longLeg().instrumentKey()  == null ||
                             (isIronCondor && (config.shortLeg2().instrumentKey() == null ||
                                              config.longLeg2() == null ||
                                              config.longLeg2().instrumentKey() == null));
        if (missingKey) {
            String msg = "Trade " + trade.tradeCode() + " readjust exit failed — instrumentKey missing. " +
                         "MANUAL INTERVENTION REQUIRED via Upstox app.";
            log.error("readjust.exit.missing_instrument_key tradeId={} isIronCondor={}", tradeId, isIronCondor);
            alertService.critical(tradeId, "readjust_exit_no_key", msg);
            setExitFailed(tradeId, "Missing instrument key — readjust exit");
            return false;
        }

        int quantity = config.lots() * config.lotSize();
        boolean success;
        if (isIronCondor) {
            success = agent5ExitClient.exitIronCondorTrade(
                    tradeId,
                    "READJUST: " + reason,
                    config.shortLeg().instrumentKey(),  config.shortLeg().action(),
                    config.longLeg().instrumentKey(),   config.longLeg().action(),
                    config.shortLeg2().instrumentKey(), config.shortLeg2().action(),
                    config.longLeg2().instrumentKey(),  config.longLeg2().action(),
                    quantity);
        } else {
            success = agent5ExitClient.exitTrade(
                    tradeId,
                    "READJUST: " + reason,
                    config.shortLeg().instrumentKey(),
                    config.shortLeg().action(),
                    config.longLeg().instrumentKey(),
                    config.longLeg().action(),
                    quantity);
        }

        if (!success) {
            String msg = "Trade " + trade.tradeCode() + " readjust EXIT FAILED — Agent 5 could not close. " +
                         "Position may still be open on Upstox. MANUAL INTERVENTION REQUIRED. " +
                         "Agent 3 will retry on the next 5-minute cycle.";
            log.error("readjust.exit.agent5_failed tradeId={}", tradeId);
            alertService.critical(tradeId, "readjust_exit_failed", msg);
            setExitFailed(tradeId, "Agent 5 exit call failed — readjust path");
            return false;
        }

        log.info("readjust.exit.success tradeId={} tradeCode={}", tradeId, trade.tradeCode());
        return true;
    }

    /**
     * Selects the relaxed G1 PoP floor based on current VIX.
     * VIX from EvaluationResponse is already fetched for the cycle — no extra API call.
     */
    private BigDecimal selectRelaxedPop(BigDecimal vix) {
        if (vix != null && vix.compareTo(props.getReadjustVixStressThreshold()) > 0) {
            log.info("readjust.pop_gate stressed vix={} floor={}", vix, props.getReadjustPopStressedVix());
            return props.getReadjustPopStressedVix();
        }
        log.info("readjust.pop_gate normal vix={} floor={}", vix, props.getReadjustPopNormalVix());
        return props.getReadjustPopNormalVix();
    }

    private int calculateDte(LocalDate expiryDate) {
        if (expiryDate == null) return 0;
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
        return (int) Math.max(0, days);
    }

    private void setExitFailed(UUID tradeId, String reason) {
        try {
            jdbc.update("UPDATE trades SET status = 'EXIT_FAILED', close_reason = ? WHERE id = ?",
                    reason, tradeId);
        } catch (Exception e) {
            log.error("readjust.set_exit_failed.db_error tradeId={} error={}", tradeId, e.getMessage());
        }
    }
}
