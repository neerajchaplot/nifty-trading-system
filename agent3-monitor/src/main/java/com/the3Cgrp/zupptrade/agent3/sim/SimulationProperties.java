package com.the3Cgrp.zupptrade.agent3.sim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code --simulation.*}. Active only when the simulation profile is on.
 */
@ConfigurationProperties("simulation")
public class SimulationProperties {

    /** Scenario folder name under {@link #scenariosDir}. Required in simulation mode. */
    private String scenario;

    /** Base directory that holds scenario folders. */
    private String scenariosDir = "test-data/simulation/scenarios";

    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }

    public String getScenariosDir() { return scenariosDir; }
    public void setScenariosDir(String scenariosDir) { this.scenariosDir = scenariosDir; }
}
