package com.the3Cgrp.zupptrade.agent3.sim;

import com.the3Cgrp.zupptrade.agent3.service.MonitorSchedulerService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Runs ONE monitoring cycle on demand, bypassing the wall-clock @Scheduled trigger (which is
 * disabled under the simulation profile). The conductor advances the sim clock, then calls this
 * once per virtual tick — walking a multi-day scenario in seconds.
 *
 * Active only under the {@code simulation} profile.
 */
@RestController
@RequestMapping("/sim")
@Profile("simulation")
public class SimRunController {

    private final MonitorSchedulerService scheduler;

    public SimRunController(MonitorSchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping("/run-cycle")
    public Map<String, Object> runCycle() {
        scheduler.runCycleOnce();
        return Map.of("ran", true);
    }
}
