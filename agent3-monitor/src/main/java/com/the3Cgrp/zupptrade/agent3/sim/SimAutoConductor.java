package com.the3Cgrp.zupptrade.agent3.sim;

import com.the3Cgrp.zupptrade.agent3.service.MonitorSchedulerService;
import com.the3Cgrp.zupptrade.agent3.service.TradeMonitorReader;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-driving simulation conductor (Increment 2). Replaces the external {@code run_scenario} script:
 * on a real timer it advances the virtual clock by the scenario's {@code step} and runs one monitoring
 * cycle per in-market tick, walking a multi-day scenario to its EXIT in a couple of minutes — with no
 * curl and no clock/run-cycle calls from outside.
 *
 * <p><b>Arm-on-active-trade.</b> The walk does NOT start on boot. While no ACTIVE trade exists (and
 * {@code require-active-trade=true}) the clock stays parked at the scenario entry, so no candles are
 * consumed before you've placed a trade from the UI. The moment a trade goes ACTIVE the conductor
 * snaps the clock to the scenario start and walks; when the trade closes it disarms and waits again
 * (place another trade → it re-arms and walks from the top).
 *
 * <p><b>Production safety.</b> {@code @Profile("simulation")} — this bean is never created off-sim, so
 * it adds no scheduled task and cannot touch a production run. It is additionally inert unless
 * {@code simulation.auto-run.enabled=true} is set for the sim run.
 */
@Component
@Profile("simulation")
public class SimAutoConductor {

    private static final Logger log = LoggerFactory.getLogger(SimAutoConductor.class);

    // NSE cash-session window, IST — matches the scenario timeline and run_scenario.sh.
    private static final ZoneId    IST          = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final int       SKIP_GUARD   = 100_000; // bound the off-hours fast-forward per tick

    private final SimulationProperties props;
    private final SimClockService clock;
    private final MonitorSchedulerService scheduler;
    private final TradeMonitorReader tradeReader;
    private final ScenarioReader scenarioReader;

    // Scenario clock window, parsed once from scenario.yaml.
    private Instant start;
    private Instant end;
    private Duration step;
    private boolean windowValid;

    // Walk state (single-threaded @Scheduled invoker — no locking needed).
    private boolean armed;
    private boolean completeLogged;

    public SimAutoConductor(SimulationProperties props,
                            SimClockService clock,
                            MonitorSchedulerService scheduler,
                            TradeMonitorReader tradeReader,
                            ScenarioReader scenarioReader) {
        this.props = props;
        this.clock = clock;
        this.scheduler = scheduler;
        this.tradeReader = tradeReader;
        this.scenarioReader = scenarioReader;
        parseWindow();
    }

    /**
     * One conductor beat. Real cadence is {@code simulation.auto-run.interval-ms} (default 2s); each
     * in-market beat advances the virtual clock one {@code step} and runs one monitoring cycle.
     */
    @Scheduled(fixedDelayString = "${simulation.auto-run.interval-ms:2000}")
    public void tick() {
        if (!props.getAutoRun().isEnabled() || !windowValid) {
            return;
        }

        boolean hasTrade = !tradeReader.findAllActive().isEmpty();

        // Gate: park at the scenario start until there is something to monitor.
        if (props.getAutoRun().isRequireActiveTrade() && !hasTrade) {
            if (armed) {
                armed = false;
                log.info("sim.autorun.disarm — no active trade; parking clock at scenario start {}", start);
            }
            // Pin to entry, but only write when it actually needs moving (avoids per-beat log/DB churn).
            if (!start.equals(clock.current())) {
                clock.set(start);
            }
            completeLogged = false;
            return;
        }

        // First tick with a trade present → arm and start from the top.
        if (!armed) {
            armed = true;
            completeLogged = false;
            clock.set(start);
            log.info("sim.autorun.arm — active trade detected; walking scenario from {} to {} step={}",
                    start, end, step);
            if (isMarketHours(start)) {
                scheduler.runCycleOnce();
            }
            return;
        }

        // Armed walk: advance to the next in-market instant and run one cycle.
        Instant now = clock.current();
        if (!now.isBefore(end)) {
            if (!completeLogged) {
                completeLogged = true;
                log.info("sim.autorun.complete — reached scenario end {} (trade still open: held to expiry)", end);
            }
            return;
        }

        Instant next = now.plus(step);
        int guard = 0;
        while (next.isBefore(end) && !isMarketHours(next) && guard++ < SKIP_GUARD) {
            next = next.plus(step);   // fast-forward through nights / weekends within this beat
        }
        if (next.isAfter(end)) {
            next = end;
        }
        clock.set(next);

        if (isMarketHours(next)) {
            scheduler.runCycleOnce();
        }
    }

    private boolean isMarketHours(Instant t) {
        ZonedDateTime z = t.atZone(IST);
        DayOfWeek d = z.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime lt = z.toLocalTime();
        return !lt.isBefore(MARKET_OPEN) && !lt.isAfter(MARKET_CLOSE);
    }

    /** Parse {@code clock.start/end/step} from scenario.yaml. On any problem the conductor stays inert. */
    private void parseWindow() {
        Path yaml = scenarioReader.dir().resolve("scenario.yaml");
        try {
            String text = Files.readString(yaml);
            String startRaw = first(text, "start");
            String endRaw   = first(text, "end");
            String stepRaw  = first(text, "step");
            if (startRaw == null || endRaw == null) {
                log.warn("sim.autorun.disabled — scenario.yaml has no clock.start/end; conductor inert ({})", yaml);
                return;
            }
            this.start = OffsetDateTime.parse(startRaw).toInstant();
            this.end   = OffsetDateTime.parse(endRaw).toInstant();
            int minutes = (stepRaw == null) ? 5 : Integer.parseInt(stepRaw.replaceAll("[^0-9]", ""));
            this.step = Duration.ofMinutes(Math.max(1, minutes));
            this.windowValid = true;
            log.info("sim.autorun.window start={} end={} step={} (enabled={}, requireActiveTrade={})",
                    start, end, step, props.getAutoRun().isEnabled(), props.getAutoRun().isRequireActiveTrade());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + yaml, e);
        } catch (RuntimeException e) {
            log.warn("sim.autorun.disabled — could not parse clock window from {}: {}", yaml, e.getMessage());
        }
    }

    /** First {@code key: value} match (ignores comments/indentation), or null. */
    private static String first(String text, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":\\s*(\\S+)\\s*$").matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
