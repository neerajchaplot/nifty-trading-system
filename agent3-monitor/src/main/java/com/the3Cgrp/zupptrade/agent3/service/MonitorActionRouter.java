package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.client.Agent5ExitClient;
import com.the3Cgrp.zupptrade.agent3.dto.EvaluationResponse;
import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.core.security.AuthenticatedUser;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import com.the3Cgrp.zupptrade.ledger.LedgerEventType;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.ledger.payload.TradeCloseInitiatedPayload;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Routes a monitoring decision (HOLD/WATCH/READJUST/EXIT/PAUSE) to its side effect.
 *
 * Extracted from MonitorSchedulerService so the SAME routing is used by both callers:
 *   - the scheduled 5-minute monitoring cycle, and
 *   - the on-demand POST /evaluate?act=true seam (sandbox / simulation only).
 * One source of truth — the two paths can never diverge.
 *
 * MUST be invoked OUTSIDE any JPA transaction: it performs Agent 5 HTTP calls and trade
 * status updates (CLAUDE.md rule — never call external APIs inside a transaction).
 *
 * Design: Strategy dispatch over MonitorAction; the EXIT branch carries the duplicate-exit
 * guard (EXIT_IN_PROGRESS) and ledger/alert side effects unchanged from the original.
 */
@Service
public class MonitorActionRouter {

    private static final Logger log = LoggerFactory.getLogger(MonitorActionRouter.class);

    private final ReadjustmentService readjustmentService;
    private final Agent5ExitClient    agent5ExitClient;
    private final AlertService        alertService;
    private final JdbcTemplate        jdbc;
    private final TradeLedgerService  ledger;
    private final TradeMonitorReader  tradeReader;
    private final JsonUtil            jsonUtil;
    private final UserContext         userContext;

    public MonitorActionRouter(ReadjustmentService readjustmentService,
                               Agent5ExitClient agent5ExitClient,
                               AlertService alertService,
                               JdbcTemplate jdbc,
                               TradeLedgerService ledger,
                               TradeMonitorReader tradeReader,
                               JsonUtil jsonUtil,
                               UserContext userContext) {
        this.readjustmentService = readjustmentService;
        this.agent5ExitClient    = agent5ExitClient;
        this.alertService        = alertService;
        this.jdbc                = jdbc;
        this.ledger              = ledger;
        this.tradeReader         = tradeReader;
        this.jsonUtil            = jsonUtil;
        this.userContext         = userContext;
    }

    /**
     * Loads trade + monitor config by id, then routes the decision.
     * Used by the on-demand /evaluate?act=true seam, where only the trade id and the
     * evaluation response are in scope. No-op (logged) if the trade or its config is absent.
     */
    public void applyById(UUID tradeId, EvaluationResponse response) {
        Optional<TradeMonitorData> tradeOpt = tradeReader.findById(tradeId);
        if (tradeOpt.isEmpty()) {
            log.warn("agent3.action.applyById.trade_not_found tradeId={}", tradeId);
            return;
        }
        TradeMonitorData trade = tradeOpt.get();
        if (trade.monitorConfigJson() == null) {
            log.warn("agent3.action.applyById.no_config tradeId={} tradeCode={}", tradeId, trade.tradeCode());
            return;
        }
        MonitorConfigDto config = jsonUtil.fromJson(trade.monitorConfigJson(), MonitorConfigDto.class);
        apply(trade, config, response);
    }

    /** Routes a decision to its side effect (HOLD/WATCH = no-op; READJUST/EXIT/PAUSE act). */
    public void apply(TradeMonitorData trade, MonitorConfigDto config, EvaluationResponse response) {
        // Bind the trade owner as the acting identity for the duration of this routing so that any
        // agent→agent call (Agent5 exit, readjustment) forwards X-User-Id and Agent5's ownership
        // guard is actively satisfied (not merely bypassed) + the action is attributed in audit.
        // Only when NO user is already acting: on the on-demand /evaluate?act=true seam a real user
        // may be present — leave their identity so Agent5 verifies THEY own the trade (403 if not).
        boolean bound = false;
        if (!userContext.isAuthenticated() && trade.userProfileId() != null) {
            userContext.set(new AuthenticatedUser(trade.userProfileId(), null, false, null));
            bound = true;
        }
        try {
            switch (response.action()) {
                case HOLD, WATCH -> { /* normal — already logged by evaluation service */ }

                case READJUST -> readjustmentService.handle(trade, config, response);

                case EXIT -> triggerExit(trade, config, response);

                case PAUSE -> {
                    log.warn("agent3.scheduler.vix_extreme_pause tradeId={} tradeCode={}",
                            trade.tradeId(), trade.tradeCode());
                    alertService.warning(trade.tradeId(), "vix_extreme_pause",
                            "Trade " + trade.tradeCode() + " monitoring PAUSED — VIX is in Extreme territory (>24). " +
                            "Auto-exit suppressed. Monitor manually. Trade remains ACTIVE.");
                }
            }
        } finally {
            if (bound) userContext.clear();   // never leak the owner identity onto the pooled thread
        }
    }

    /**
     * Executes the EXIT flow for a single trade.
     *
     * 1. Set trade status to EXIT_IN_PROGRESS immediately (prevents duplicate exits next cycle)
     * 2. Call Agent 5 to place reverse MARKET orders
     * 3. On failure: set EXIT_FAILED + write CRITICAL alert (Agent 3 retries next cycle)
     *
     * On success, Agent 5 sets the trade to CLOSED in DB — no additional update needed here.
     */
    private void triggerExit(TradeMonitorData trade, MonitorConfigDto config,
                              EvaluationResponse response) {
        UUID tradeId = trade.tradeId();
        log.error("agent3.scheduler.exit_required tradeId={} tradeCode={} reason='{}' pnl={}",
                tradeId, trade.tradeCode(), response.reason(), response.markToMarketPnl());

        // Set EXIT_IN_PROGRESS so the next 5-min cycle skips this trade
        try {
            jdbc.update("UPDATE trades SET status = 'EXIT_IN_PROGRESS' WHERE id = ? AND status IN ('ACTIVE','EXIT_FAILED')",
                    tradeId);
        } catch (Exception e) {
            log.error("agent3.exit.status_update.failed tradeId={} error={}", tradeId, e.getMessage());
            // If we can't set the intermediate status, do NOT proceed with the exit call —
            // the duplicate prevention guard won't work without it.
            alertService.critical(tradeId, "exit_status_update_failed",
                    "Trade " + trade.tradeCode() + " exit aborted — could not set EXIT_IN_PROGRESS status. " +
                    "DB error. MANUAL INTERVENTION REQUIRED.");
            return;
        }

        // Ledger: TRADE_CLOSE_INITIATED — record trigger context before calling Agent 5
        ledger.record(tradeId, LedgerEventType.TRADE_CLOSE_INITIATED,
                new TradeCloseInitiatedPayload(response.reason(), response.markToMarketPnl(),
                        "AGENT3:SCHEDULER"),
                "AGENT3:SCHEDULER");

        // Validate that we have the instrument keys needed to build the exit request
        if (config.shortLeg() == null || config.shortLeg().instrumentKey() == null ||
            config.longLeg()  == null || config.longLeg().instrumentKey()  == null) {
            String msg = "Trade " + trade.tradeCode() + " exit failed — instrumentKey missing from monitor_config. " +
                         "MANUAL INTERVENTION REQUIRED via Upstox app.";
            log.error("agent3.exit.missing_instrument_key tradeId={}", tradeId);
            alertService.critical(tradeId, "exit_missing_instrument_key", msg);
            setExitFailed(tradeId, "Missing instrument key in monitor_config");
            return;
        }

        int quantity = config.lots() * config.lotSize();
        boolean isIronCondor = config.shortLeg2() != null && config.longLeg2() != null;
        boolean success;

        if (isIronCondor) {
            if (config.shortLeg2().instrumentKey() == null || config.longLeg2().instrumentKey() == null) {
                String msg = "Trade " + trade.tradeCode() + " IC exit failed — CE leg instrumentKey missing. MANUAL INTERVENTION REQUIRED.";
                log.error("agent3.exit.missing_instrument_key.ce_legs tradeId={}", tradeId);
                alertService.critical(tradeId, "exit_missing_instrument_key", msg);
                setExitFailed(tradeId, "Missing CE leg instrument key in monitor_config");
                return;
            }
            success = agent5ExitClient.exitIronCondorTrade(
                    tradeId, response.reason(),
                    config.shortLeg().instrumentKey(),  config.shortLeg().action(),
                    config.longLeg().instrumentKey(),   config.longLeg().action(),
                    config.shortLeg2().instrumentKey(), config.shortLeg2().action(),
                    config.longLeg2().instrumentKey(),  config.longLeg2().action(),
                    quantity);
        } else {
            success = agent5ExitClient.exitTrade(
                    tradeId, response.reason(),
                    config.shortLeg().instrumentKey(), config.shortLeg().action(),
                    config.longLeg().instrumentKey(),  config.longLeg().action(),
                    quantity);
        }

        if (!success) {
            String msg = "Trade " + trade.tradeCode() + " EXIT FAILED — Agent 5 could not place exit orders. " +
                         "Position may still be open on Upstox. MANUAL INTERVENTION REQUIRED. " +
                         "Agent 3 will retry on the next 5-minute cycle.";
            log.error("agent3.exit.agent5_failed tradeId={} tradeCode={}", tradeId, trade.tradeCode());
            alertService.critical(tradeId, "exit_failed", msg);
            setExitFailed(tradeId, "Agent 5 exit call failed");
        } else {
            // Agent 5 set the trade to CLOSED — log confirmation
            log.info("agent3.exit.success tradeId={} tradeCode={} reason='{}'",
                    tradeId, trade.tradeCode(), response.reason());
        }
    }

    private void setExitFailed(UUID tradeId, String reason) {
        try {
            jdbc.update("UPDATE trades SET status = 'EXIT_FAILED', close_reason = ? WHERE id = ?",
                    reason, tradeId);
        } catch (Exception e) {
            log.error("agent3.exit_failed.update.failed tradeId={} error={}", tradeId, e.getMessage());
        }
    }
}
