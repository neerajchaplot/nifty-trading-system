package com.the3Cgrp.zupptrade.agent2.client;

import com.the3Cgrp.zupptrade.agent2.client.model.MarketSnapshot;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioData;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioReader;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Simulation spot/VIX — reads the scenario folder at the fixed entry instant (Agents 1/2 are
 * point-in-time). Replaces {@code UpstoxMarketDataClient} under the {@code simulation} profile.
 */
@Component
@Profile("simulation")
public class SimulatedMarketDataClient implements MarketDataClient {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ScenarioReader scenario;
    private final Clock clock;

    public SimulatedMarketDataClient(ScenarioReader scenario, Clock clock) {
        this.scenario = scenario;
        this.clock = clock;
    }

    @Override
    public MarketSnapshot fetchSnapshot() {
        Instant t = clock.instant();
        var sv = scenario.spotVixAt(t);
        BigDecimal spot = sv.map(ScenarioData.SpotVix::spot).orElse(BigDecimal.ZERO);
        BigDecimal vix  = sv.map(ScenarioData.SpotVix::vix).orElse(BigDecimal.ZERO);
        return new MarketSnapshot(spot, vix, LocalDateTime.ofInstant(t, IST));
    }
}
