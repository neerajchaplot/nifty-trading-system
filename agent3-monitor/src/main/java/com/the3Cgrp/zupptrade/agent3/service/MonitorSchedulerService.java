package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.client.Agent2RecommendClient;
import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.dto.EvaluationResponse;
import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.agent3.model.TradeMonitorData;
import com.the3Cgrp.zupptrade.agent3.util.JsonUtil;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxPositionClient;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainRow;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 *   6. Delegates each outcome to MonitorActionRouter (EXIT / READJUST / PAUSE side effects) —
 *      the same router used by the on-demand /evaluate?act=true seam, so routing never diverges
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

    private final TradeMonitorReader            tradeReader;
    private final MarketDataService             marketDataService;
    private final MonitorEvaluationService      evaluationService;
    private final PositionReconciliationService reconciliationService;
    private final JsonUtil                      jsonUtil;
    private final MonitoringProperties          props;
    private final UpstoxPositionClient          positionClient;
    private final Agent2RecommendClient         agent2RecommendClient;
    private final ObjectMapper                  objectMapper;
    private final Clock                         clock;
    private final MonitorActionRouter           actionRouter;
    private final boolean                       autoRunScheduler;

    public MonitorSchedulerService(TradeMonitorReader tradeReader,
                                    MarketDataService marketDataService,
                                    MonitorEvaluationService evaluationService,
                                    PositionReconciliationService reconciliationService,
                                    JsonUtil jsonUtil,
                                    MonitoringProperties props,
                                    UpstoxPositionClient positionClient,
                                    Agent2RecommendClient agent2RecommendClient,
                                    ObjectMapper objectMapper,
                                    Clock clock,
                                    MonitorActionRouter actionRouter,
                                    Environment environment) {
        this.tradeReader              = tradeReader;
        this.marketDataService        = marketDataService;
        this.evaluationService        = evaluationService;
        this.reconciliationService    = reconciliationService;
        this.jsonUtil                 = jsonUtil;
        this.props                    = props;
        this.positionClient           = positionClient;
        this.agent2RecommendClient    = agent2RecommendClient;
        this.objectMapper             = objectMapper;
        this.clock                    = clock;
        this.actionRouter             = actionRouter;
        // Under the simulation profile the wall-clock @Scheduled trigger is disabled — the
        // conductor drives cycles deterministically via POST /sim/run-cycle instead.
        this.autoRunScheduler         = !environment.acceptsProfiles(Profiles.of("simulation"));
    }

    // zone is IST: the container runs UTC but the cron window (9-15) and isMarketHours() are IST.
    @Scheduled(cron = "${agent3.monitoring.scheduler-cron}", zone = "${agent3.monitoring.scheduler-zone:Asia/Kolkata}")
    @SchedulerLock(name = "agent3_monitoring_cycle",
                   lockAtMostFor = "PT4M30S",   // safety net: released if process dies mid-cycle
                   lockAtLeastFor = "PT1M")      // prevents immediate re-run if cycle completes fast
    public void runMonitoringCycle() {
        if (!autoRunScheduler) return;   // simulation drives cycles via POST /sim/run-cycle
        runCycleOnce();
    }

    /** One monitoring pass. Public so the simulation conductor can trigger it per virtual tick. */
    public void runCycleOnce() {
        if (!isMarketHours()) return;

        List<TradeMonitorData> allTrades = tradeReader.findAllActive();
        if (allTrades.isEmpty()) {
            log.debug("agent3.scheduler.no_active_trades");
            return;
        }

        log.info("agent3.scheduler.cycle_start active_trades={}", allTrades.size());
        // Real wall-clock, NOT the injected Clock: measures actual cycle execution time
        // for logging. Virtual (simulation) time does not advance within a single cycle.
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

                actionRouter.apply(trade, config, response);
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
            // For IC: PE SELL, PE BUY, CE SELL, CE BUY. For 2-leg: one SELL + one BUY.
            BigDecimal peSellFill = null, peBuyFill = null;
            BigDecimal ceSellFill = null, ceBuyFill = null;

            for (JsonNode fill : fills) {
                String action      = fill.path("action").asText();
                String optionType  = fill.path("optionType").asText(""); // "PE" or "CE"
                String avgPrice    = fill.path("averageFillPrice").asText();
                if (avgPrice.isBlank()) continue;
                BigDecimal price = new BigDecimal(avgPrice);

                if ("SELL".equals(action)) {
                    if ("CE".equals(optionType)) ceSellFill = price;
                    else peSellFill = price; // PE or 2-leg
                } else if ("BUY".equals(action)) {
                    if ("CE".equals(optionType)) ceBuyFill = price;
                    else peBuyFill = price;  // PE or 2-leg
                }
            }

            if (peSellFill == null || peBuyFill == null) {
                log.error("agent3.scheduler.seed_fail.missing_prices tradeId={} tradeCode={} peSell={} peBuy={}",
                        trade.tradeId(), trade.tradeCode(), peSellFill, peBuyFill);
                return null;
            }

            Optional<MonitorConfigDto> seeded = agent2RecommendClient.fetchMonitorConfig(
                    trade.tradeId(), peSellFill, peBuyFill, ceSellFill, ceBuyFill);

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



    // ── MARKET HOURS GUARD ────────────────────────────────────────────────────

    private boolean isMarketHours() {
        LocalTime now   = LocalTime.now(clock);
        LocalTime open  = LocalTime.of(props.getMarketOpenHour(),  props.getMarketOpenMinute());
        LocalTime close = LocalTime.of(props.getMarketCloseHour(), props.getMarketCloseMinute());
        return now.isAfter(open) && now.isBefore(close);
    }
}
