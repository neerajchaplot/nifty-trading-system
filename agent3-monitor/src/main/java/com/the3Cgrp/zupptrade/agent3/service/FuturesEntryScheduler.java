package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.client.Agent5FuturesClient;
import com.the3Cgrp.zupptrade.agent3.config.FuturesEntryProperties;
import com.the3Cgrp.zupptrade.agent3.engine.EntryDecision;
import com.the3Cgrp.zupptrade.agent3.engine.FuturesEntryStateMachine;
import com.the3Cgrp.zupptrade.agent3.service.FuturesPlanReader.FuturesPlanRow;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient.IntradayCandle;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Watches ARMED futures plans for their entry trigger (spec §3) — separate from the options
 * P&L loop (which reads the {@code trades} table and never sees these). Each 5-min tick:
 *   1. read watchable plans (ARMED / BREAK_DETECTED) for today
 *   2. fetch today's completed 5-min Nifty candles once
 *   3. run the entry FSM per plan; on CONFIRMED hand the plan to Agent 5, else advance status
 *
 * The Agent 5 GTT (ENTRY+TARGET+STOPLOSS OCO) then owns the trade — Agent 3 does no P&L work
 * on futures. A fired plan leaves the watchable set (status CONFIRMED), so it never re-fires.
 */
@Service
public class FuturesEntryScheduler {

    private static final Logger log = LoggerFactory.getLogger(FuturesEntryScheduler.class);

    private final FuturesPlanReader reader;
    private final FuturesEntryStateMachine fsm;
    private final UpstoxIntradayCandleClient intradayClient;
    private final Agent5FuturesClient agent5Client;
    private final AlertService alertService;
    private final FuturesEntryProperties props;
    private final Clock clock;
    private final boolean autoRunScheduler;

    public FuturesEntryScheduler(FuturesPlanReader reader,
                                 FuturesEntryStateMachine fsm,
                                 UpstoxIntradayCandleClient intradayClient,
                                 Agent5FuturesClient agent5Client,
                                 AlertService alertService,
                                 FuturesEntryProperties props,
                                 Clock clock,
                                 Environment environment) {
        this.reader = reader;
        this.fsm = fsm;
        this.intradayClient = intradayClient;
        this.agent5Client = agent5Client;
        this.alertService = alertService;
        this.props = props;
        this.clock = clock;
        // Simulation drives cycles deterministically; disable the wall-clock trigger there.
        this.autoRunScheduler = !environment.acceptsProfiles(Profiles.of("simulation"));
    }

    @Scheduled(cron = "${agent3.futures.scheduler-cron:0 */5 9-14 * * MON-FRI}",
               zone = "${agent3.futures.scheduler-zone:Asia/Kolkata}")
    @SchedulerLock(name = "agent3_futures_entry_cycle",
                   lockAtMostFor = "PT4M30S", lockAtLeastFor = "PT30S")
    public void runEntryCycle() {
        if (!props.isEnabled() || !autoRunScheduler) return;
        runCycleOnce();
    }

    /** One entry-watch pass. Public so the simulation conductor / tests can drive it directly. */
    public void runCycleOnce() {
        List<FuturesPlanRow> rows = reader.findWatchable();
        if (rows.isEmpty()) {
            log.debug("agent3.futures.no_watchable");
            return;
        }

        List<IntradayCandle> candles = fetchCompletedCandles();
        if (candles.isEmpty()) {
            log.warn("agent3.futures.no_candles watchable={} — skipping cycle", rows.size());
            return;
        }

        boolean pastCutoff = pastCutoff();
        log.info("agent3.futures.cycle_start watchable={} candles={} pastCutoff={}",
                rows.size(), candles.size(), pastCutoff);

        for (FuturesPlanRow row : rows) {
            try {
                evaluatePlan(row, candles, pastCutoff);
            } catch (Exception e) {
                log.error("agent3.futures.eval_error planCode={} error={}", row.planCode(), e.getMessage(), e);
            }
        }
    }

    private void evaluatePlan(FuturesPlanRow row, List<IntradayCandle> candles, boolean pastCutoff) {
        FutureArmType arm = FutureArmType.valueOf(row.primaryArm());
        EntryDecision d = fsm.evaluate(candles, row.entryPrice(), arm.direction(),
                props.getRequiredConsecutiveCloses(), pastCutoff);

        if (d.state() == row.status()) {
            return; // no change (still ARMED, or still BREAK_DETECTED)
        }

        switch (d.state()) {
            case CONFIRMED -> {
                // Persist ARMED/BREAK_DETECTED → CONFIRMED BEFORE the handoff: Agent 5's GTT placement
                // hard-requires the plan to already be CONFIRMED. Without this the handoff is rejected
                // and the plan dies EXECUTION_FAILED. (schema V116: activated_at = submitted to Agent 5)
                reader.markConfirmed(row.id());
                Agent5FuturesClient.HandoffResult handoff = agent5Client.placeGtt(row.id());
                if (handoff.reached()) {
                    // Agent 5 owns the outcome (FILLED, or its own EXECUTION_FAILED + alert).
                    log.info("agent3.futures.handed_off planCode={} arm={} entry={} reason={}",
                            row.planCode(), arm, row.entryPrice(), d.reason());
                } else {
                    // Reached-but-rejected OR unreachable → fail fast, no retry; user acts on the alert.
                    reader.markExecutionFailed(row.id());
                    alertService.critical(row.id(), "futures_gtt_handoff_failed",
                            "Futures entry confirmed for " + row.planCode() + " but the Agent 5 handoff failed — "
                                    + handoff.failureReason() + ". No order placed, no retry — manual check required.");
                    log.error("agent3.futures.handoff_failed planCode={} reason={} — marked EXECUTION_FAILED",
                            row.planCode(), handoff.failureReason());
                }
            }
            case BREAK_DETECTED, INVALIDATED, EXPIRED -> {
                reader.updateStatus(row.id(), d.state());
                log.info("agent3.futures.transition planCode={} {} -> {} reason={}",
                        row.planCode(), row.status(), d.state(), d.reason());
            }
            default -> { /* ARMED — nothing to do */ }
        }
    }

    /** Today's completed 5-min candles — the in-progress candle is dropped. */
    private List<IntradayCandle> fetchCompletedCandles() {
        List<IntradayCandle> all = intradayClient.fetchNiftyIntradayCandles(props.getCandleIntervalMinutes());
        OffsetDateTime now = OffsetDateTime.now(clock);
        int interval = props.getCandleIntervalMinutes();
        return all.stream()
                .filter(c -> !c.time().plusMinutes(interval).isAfter(now))
                .toList();
    }

    private boolean pastCutoff() {
        LocalTime nowLocal = OffsetDateTime.now(clock)
                .atZoneSameInstant(ZoneId.of(props.getZone()))
                .toLocalTime();
        return !nowLocal.isBefore(props.getCutoffTime());
    }
}
