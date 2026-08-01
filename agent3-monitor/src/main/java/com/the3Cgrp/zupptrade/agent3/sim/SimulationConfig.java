package com.the3Cgrp.zupptrade.agent3.sim;

import com.the3Cgrp.zupptrade.shared.simulation.ScenarioReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wires the scenario folder into a {@link ScenarioReader} bean under the simulation profile.
 * Fails fast at startup if {@code --simulation.scenario} is missing or the folder is not found.
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
        log.warn("SIMULATION scenario '{}' loaded from {}", props.getScenario(), dir.toAbsolutePath());
        return new ScenarioReader(dir);
    }

    /** Resolves the folder whether the process runs from the repo root or a module dir (mvn spring-boot:run). */
    private Path resolve(SimulationProperties props) {
        Path[] roots = {
                Path.of(props.getScenariosDir()),                 // cwd = repo root
                Path.of("..", props.getScenariosDir()),           // cwd = module dir
                Path.of("..", "..", props.getScenariosDir())
        };
        for (Path root : roots) {
            Path dir = root.resolve(props.getScenario());
            if (Files.isDirectory(dir)) return dir;
        }
        throw new IllegalStateException("Scenario folder '" + props.getScenario()
                + "' not found under '" + props.getScenariosDir() + "' (cwd="
                + Path.of("").toAbsolutePath() + "). Pass --simulation.scenarios-dir to override.");
    }
}
