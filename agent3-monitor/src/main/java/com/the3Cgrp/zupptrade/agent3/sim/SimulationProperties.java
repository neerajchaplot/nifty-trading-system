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

    /** Hands-free auto-conductor settings (binds {@code simulation.auto-run.*}). */
    private AutoRun autoRun = new AutoRun();

    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }

    public String getScenariosDir() { return scenariosDir; }
    public void setScenariosDir(String scenariosDir) { this.scenariosDir = scenariosDir; }

    public AutoRun getAutoRun() { return autoRun; }
    public void setAutoRun(AutoRun autoRun) { this.autoRun = autoRun; }

    /**
     * Config for the self-driving sim conductor ({@code SimAutoConductor}). All defaults are inert
     * ({@code enabled=false}) so the conductor does nothing unless explicitly turned on for a sim run.
     */
    public static class AutoRun {
        /** Master switch. When false the conductor never advances the clock (today's manual behaviour). */
        private boolean enabled = false;
        /** When true, the walk stays parked at the scenario start until an ACTIVE trade exists to monitor. */
        private boolean requireActiveTrade = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isRequireActiveTrade() { return requireActiveTrade; }
        public void setRequireActiveTrade(boolean requireActiveTrade) { this.requireActiveTrade = requireActiveTrade; }
    }
}
