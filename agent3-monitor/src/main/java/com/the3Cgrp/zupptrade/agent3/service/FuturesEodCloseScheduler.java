package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.client.Agent5FuturesCloseClient;
import com.the3Cgrp.zupptrade.agent3.config.FuturesEntryProperties;
import com.the3Cgrp.zupptrade.agent3.service.FuturesPlanReader.FuturesPlanRow;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * End-of-day settlement for futures (spec §6.7). After the intraday square-off, picks every FILLED
 * plan for the day and asks Agent 5 to book its realized P&L (resolved by tag) → status CLOSED.
 * Agent 4 then reports on the CLOSED plans. Runs once at 15:30 IST.
 */
@Service
public class FuturesEodCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(FuturesEodCloseScheduler.class);

    private final FuturesPlanReader reader;
    private final Agent5FuturesCloseClient closeClient;
    private final FuturesEntryProperties props;
    private final boolean autoRunScheduler;

    public FuturesEodCloseScheduler(FuturesPlanReader reader,
                                    Agent5FuturesCloseClient closeClient,
                                    FuturesEntryProperties props,
                                    Environment environment) {
        this.reader = reader;
        this.closeClient = closeClient;
        this.props = props;
        this.autoRunScheduler = !environment.acceptsProfiles(Profiles.of("simulation"));
    }

    @Scheduled(cron = "${agent3.futures.eod-close-cron:0 30 15 * * MON-FRI}",
               zone = "${agent3.futures.scheduler-zone:Asia/Kolkata}")
    @SchedulerLock(name = "agent3_futures_eod_close",
                   lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
    public void runEodClose() {
        if (!props.isEnabled() || !autoRunScheduler) return;
        runCycleOnce();
    }

    /** One EOD settlement pass. Public so the simulation conductor / tests can drive it directly. */
    public void runCycleOnce() {
        List<FuturesPlanRow> filled = reader.findFilledToday();
        if (filled.isEmpty()) {
            log.debug("agent3.futures.eod.none");
            return;
        }
        log.info("agent3.futures.eod.start filled={}", filled.size());
        for (FuturesPlanRow row : filled) {
            try {
                closeClient.close(row.id());
            } catch (Exception e) {
                log.error("agent3.futures.eod.error planCode={} error={}", row.planCode(), e.getMessage(), e);
            }
        }
    }
}
