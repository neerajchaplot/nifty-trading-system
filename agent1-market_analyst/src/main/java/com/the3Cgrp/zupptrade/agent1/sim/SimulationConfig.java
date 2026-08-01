package com.the3Cgrp.zupptrade.agent1.sim;

import com.the3Cgrp.zupptrade.shared.simulation.ScenarioMeta;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;

/**
 * Simulation wiring for Agent 1. Agent 1 is point-in-time (it scores one snapshot), so the
 * {@link Clock} is FIXED at the scenario's entry instant — {@link SimulatedMarketInputsProvider}
 * reads every folder value "as of" that moment. No ticking clock, no sim_clock row. Mirrors the
 * Agent 2 wiring so a single scenario folder drives both.
 */
@Configuration
@Profile("simulation")
@EnableConfigurationProperties(SimulationProperties.class)
public class SimulationConfig {

    private static final Logger log = LoggerFactory.getLogger(SimulationConfig.class);

    @Bean
    public ScenarioReader scenarioReader(SimulationProperties props) {
        if (props.getScenario() == null || props.getScenario().isBlank()) {
            throw new IllegalStateException(
                    "Simulation profile is active but --simulation.scenario was not provided.");
        }
        Path dir = resolve(props);
        log.warn("SIMULATION (agent1) scenario '{}' loaded from {}", props.getScenario(), dir.toAbsolutePath());
        return new ScenarioReader(dir);
    }

    @Bean
    public ScenarioMeta scenarioMeta(ScenarioReader reader) {
        return ScenarioMeta.load(reader.dir());
    }

    /** Fixed at the scenario entry moment — Agent 1 scores once, at entry. */
    @Bean
    public Clock clock(ScenarioMeta meta) {
        log.warn("SIMULATION (agent1) fixed clock @ {}", meta.entryAt());
        return Clock.fixed(meta.entryAt(), ZoneId.of("Asia/Kolkata"));
    }

    private Path resolve(SimulationProperties props) {
        Path[] roots = {
                Path.of(props.getScenariosDir()),
                Path.of("..", props.getScenariosDir()),
                Path.of("..", "..", props.getScenariosDir())
        };
        for (Path root : roots) {
            Path dir = root.resolve(props.getScenario());
            if (Files.isDirectory(dir)) return dir;
        }
        throw new IllegalStateException("Scenario folder '" + props.getScenario()
                + "' not found under '" + props.getScenariosDir() + "' (cwd="
                + Path.of("").toAbsolutePath() + ").");
    }
}
