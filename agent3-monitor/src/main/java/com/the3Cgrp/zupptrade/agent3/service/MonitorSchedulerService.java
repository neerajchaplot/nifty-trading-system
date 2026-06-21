package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.client.Agent2RecommendClient;
import com.the3Cgrp.zupptrade.agent3.client.Agent5ExitClient;
import com.the3Cgrp.zupptrade.agent3.service.ReadjustmentService;
import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.dto.EvaluationResponse;
import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxPositionClient;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainRow;
import com.the3Cgrp.zupptrade.ledger.LedgerEventType;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.ledger.payload.TradeCloseInitiatedPayload;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drives the 5-minute monitoring cycle for all ACTIVE and EXIT_FAILED trades.
 *
 * Each cycle:
 *   1. Loads all monitorable trades from DB (ACTIVE + EXIT_FAILED) — one query
 *   2. Position reconciliation — detect external closes via Upstox positions API
 *   3. Groups remaining trades by expiry — ONE Upstox option chain call per unique expiry
 *   4. ONE VIX call for the cycle
 *   5. Evaluates each trade against the shared snapshot (no additional API calls)
 *   6. Routes actionable outcomes: EXIT → Agent 5, PAUSE → notification, READJUST → alert
 *
 * EXIT_FAILED trades re-enter the loop: Agent 3 re-evaluates and retries the exit call
 * until it succeeds or the user manually closes.
 *
 * TODO(shedlock): Wrap with @SchedulerLock once shedlock table migration lands.
 *   Protects against overlapping runs if a previous cycle takes longer than 5 min.
 */
@Service
public class MonitorSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(MonitorSchedulerService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradeMonitorReader            tradeReader;
    private final LiveMarketDataService         marketDataService;
    private final MonitorEvaluationService      evaluationService;
    private final PositionReconciliationService reconciliationService;
    private final Agent5ExitClient              agent5ExitClient;
    private final ReadjustmentService           readjustmentService;
    private final AlertService                  alertService;
    private final JdbcTemplate                  jdbc;
    private final JsonUtil                      jsonUtil;
    private final MonitoringProperties          props;
    private final UpstoxPositionClient          positionClient;
    private final TradeLedgerService            ledger;
    private final Agent2RecommendClient         agent2RecommendClient;
    private final ObjectMapper                  objectMapper;

    public MonitorSchedulerService(TradeMonitorReader tradeReader,
                                    LiveMarketDataService marketDataService,
                                    MonitorEvaluationService evaluationService,
                                    PositionReconciliationService reconciliationService,
                                    Agent5ExitClient agent5ExitClient,
                                    ReadjustmentService readjustmentService,
                                    AlertService alertService,
                                    JdbcTemplate jdbc,
                                    JsonUtil jsonUtil,
                                    MonitoringProperties props,
                                    UpstoxPositionClient positionClient,
                                    TradeLedgerService ledger,
                                    Agent2RecommendClient agent2RecommendClient,
                                    ObjectMapper objectMapper) {
        this.tradeReader              = tradeReader;
        this.marketDataService        = marketDataService;
        this.evaluationService        = evaluationService;
        this.reconciliationService    = reconciliationService;
        this.agent5ExitClient         = agent5ExitClient;
        this.readjustmentService      = readjustmentService;
        this.alertService             = alertService;
        this.jdbc                     = jdbc;
        this.jsonUtil                 = jsonUtil;
        this.props                    = props;
        this.positionClient           = positionClient;
        this.ledger                   = ledger;
        this.agent2RecommendClient    = agent2RecommendClient;
        this.objectMapper             = objectMapper;
    }

    @Scheduled(cron = "${agent3.monitoring.scheduler-cron}")
    @SchedulerLock(name = "agent3_monitoring_cycle",
                   lockAtMostFor = "PT4M30S",   // safety net: released if process dies mid-cycle
                   lockAtLeastFor = "PT1M")      // prevents immediate re-run if cycle completes fast
    public void runMonitoringCycle() {
        if (!isMarketHours()) return;

        List<TradeMonitorData> allTrades = tradeReader.findAllActive();
        if (allTrades.isEmpty()) {
            log.debug("agent3.scheduler.no_active_trades");
            return;
        }

        log.info("agent3.scheduler.cycle_start active_trades={}", allTrades.size());
        Instant cycleStart = Instant.now();

        // ── Step 1: Position reconciliation ─────────────────────────────────
        // Fetch Upstox positions once. Returns empty map if unavailable — reconciliation
        // is skipped silently; no trade is ever marked closed without confirmed data.
        Map<String, Integer> positions = positionClient.fetchNetQuantities();
        Set<java.util.UUID> externallyClosedIds = reconciliationService.reconcile(allTrades, positions);

        // Filter out externally closed trades — they no longer need evaluation
        List<TradeMonitorData> tradesToEvaluate = allTrades.stream()
                .filter(t -> !externallyClosedIds.contains(t.tradeId()))
                .toList();

        if (tradesToEvaluate.isEmpty()) {
            log.info("agent3.scheduler.cycle_complete all_trades_reconciled_as_closed elapsed_ms={}",
                    Duration.between(cycleStart, Instant.now()).toMillis());
            return;
        }

        // ── Step 2: Batch Upstox data fetch ──────────────────────────────────
        // ONE chain call per unique expiry; ONE VIX call for the whole cycle
        Set<LocalDate> expiries = tradesToEvaluate.stream()
                .map(TradeMonitorData::expiryDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<LocalDate, List<UpstoxOptionChainRow>> chainByExpiry =
                marketDataService.batchFetchChains(expiries);
        BigDecimal vix = marketDataService.fetchVix();

        int evaluated = 0;
        int errors    = 0;

        // ── Step 3: Evaluate each trade ──────────────────────────────────────
        for (TradeMonitorData trade : tradesToEvaluate) {
            try {
                MonitorConfigDto config = resolveMonitorConfig(trade);
                if (config == null) {
                    continue;
                }

                List<UpstoxOptionChainRow> chain = chainByExpiry.getOrDefault(
                        trade.expiryDate(), List.of());

                LiveMarketSnapshot snapshot = marketDataService.buildSnapshotFromChain(
                        chain, vix, config.shortLeg(), config.longLeg());

                EvaluationResponse response = evaluationService.evaluate(trade, config, snapshot);

                handleAction(trade, config, response);
                evaluated++;

            } catch (Exception e) {
                log.error("agent3.scheduler.trade_error tradeId={} tradeCode={} error={}",
                        trade.tradeId(), trade.tradeCode(), e.getMessage(), e);
                errors++;
            }
        }

        long elapsedMs = Duration.between(cycleStart, Instant.now()).toMillis();
        log.info("agent3.scheduler.cycle_complete evaluated={} errors={} elapsed_ms={}",
                evaluated, errors, elapsedMs);
    }

    // ── MONITOR CONFIG RESOLUTION ─────────────────────────────────────────────

    /**
     * Returns the MonitorConfigDto for a trade, seeding it from Agent 2 if not yet stored.
     *
     * Normal path: monitor_config already in DB (set when Agent2 /monitor-config was first called).
     *
     * Bootstrap path: entry_fills present but monitor_config is null — this happens when Agent5
     * executed the trade successfully but the /monitor-config call was never made. We parse
     * entry_fills to extract actual fill prices, call Agent2 to compute and persist the config,
     * and return the result. On the next cycle the DB column will be populated.
     *
     * Returns null (and logs) on any failure — caller skips the trade for this cycle.
     */
    private MonitorConfigDto resolveMonitorConfig(TradeMonitorData trade) {
        if (trade.monitorConfigJson() != null) {
            return jsonUtil.fromJson(trade.monitorConfigJson(), MonitorConfigDto.class);
        }

        if (trade.entryFillsJson() == null) {
            log.warn("agent3.scheduler.skip_no_config tradeId={} tradeCode={} — monitor_config and entry_fills both absent",
                    trade.tradeId(), trade.tradeCode());
            return null;
        }

        log.info("agent3.scheduler.seed_monitor_config tradeId={} tradeCode={} — calling Agent2 /monitor-config",
                trade.tradeId(), trade.tradeCode());
        try {
            JsonNode fills = objectMapper.readTree(trade.entryFillsJson());
            BigDecimal shortFillPrice = null;
            BigDecimal longFillPrice  = null;

            for (JsonNode fill : fills) {
                String action   = fill.path("action").asText();
                String avgPrice = fill.path("averageFillPrice").asText();
                if (avgPrice.isBlank()) continue;

                if ("SELL".equals(action)) {
                    shortFillPrice = new BigDecimal(avgPrice);
                } else if ("BUY".equals(action)) {
                    longFillPrice = new BigDecimal(avgPrice);
                }
            }

            if (shortFillPrice == null || longFillPrice == null) {
                log.error("agent3.scheduler.seed_fail.missing_prices tradeId={} tradeCode={} short={} long={}",
                        trade.tradeId(), trade.tradeCode(), shortFillPrice, longFillPrice);
                return null;
            }

            Optional<MonitorConfigDto> seeded = agent2RecommendClient.fetchMonitorConfig(
                    trade.tradeId(), shortFillPrice, longFillPrice);

            if (seeded.isEmpty()) {
                log.warn("agent3.scheduler.seed_fail.agent2_empty tradeId={} tradeCode={}",
                        trade.tradeId(), trade.tradeCode());
                return null;
            }

            log.info("agent3.scheduler.seed_success tradeId={} tradeCode={} — monitor_config now populated",
                    trade.tradeId(), trade.tradeCode());
            return seeded.get();

        } catch (Exception e) {
            log.error("agent3.scheduler.seed_error tradeId={} tradeCode={} error={}",
                    trade.tradeId(), trade.tradeCode(), e.getMessage(), e);
            return null;
        }
    }

    // ── ACTION ROUTING ────────────────────────────────────────────────────────

    private void handleAction(TradeMonitorData trade, MonitorConfigDto config,
                               EvaluationResponse response) {
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
        java.util.UUID tradeId = trade.tradeId();
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

        boolean success = agent5ExitClient.exitTrade(
                tradeId,
                response.reason(),
                config.shortLeg().instrumentKey(),
                config.shortLeg().action(),
                config.longLeg().instrumentKey(),
                config.longLeg().action(),
                quantity);

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

    private void setExitFailed(java.util.UUID tradeId, String reason) {
        try {
            jdbc.update("UPDATE trades SET status = 'EXIT_FAILED', close_reason = ? WHERE id = ?",
                    reason, tradeId);
        } catch (Exception e) {
            log.error("agent3.exit_failed.update.failed tradeId={} error={}", tradeId, e.getMessage());
        }
    }

    // ── MARKET HOURS GUARD ────────────────────────────────────────────────────

    private boolean isMarketHours() {
        LocalTime now   = LocalTime.now(IST);
        LocalTime open  = LocalTime.of(props.getMarketOpenHour(),  props.getMarketOpenMinute());
        LocalTime close = LocalTime.of(props.getMarketCloseHour(), props.getMarketCloseMinute());
        return now.isAfter(open) && now.isBefore(close);
    }
}
