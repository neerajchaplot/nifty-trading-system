package com.the3Cgrp.zupptrade.agent2.sim;

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
 * Simulation wiring for Agent 2. Agent 2 is point-in-time, so the {@link Clock} is FIXED at the
 * scenario's entry instant — every {@code now(clock)} then resolves to the scenario's "today",
 * and the DTE math just works with the scenario date. No ticking clock, no sim_clock row.
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
        log.warn("SIMULATION (agent2) scenario '{}' loaded from {}", props.getScenario(), dir.toAbsolutePath());
        return new ScenarioReader(dir);
    }

    @Bean
    public ScenarioMeta scenarioMeta(ScenarioReader reader) {
        return ScenarioMeta.load(reader.dir());
    }

    /** Fixed at the scenario entry moment — Agent 2 runs once, at entry. */
    @Bean
    public Clock clock(ScenarioMeta meta) {
        log.warn("SIMULATION (agent2) fixed clock @ {}", meta.entryAt());
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
