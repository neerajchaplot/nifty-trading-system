package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainRow;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioData;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Simulation market data — reads the scenario folder at the virtual-clock time instead of Upstox.
 *
 * The Upstox-specific chain rows are not needed: {@link #batchFetchChains} returns an empty map,
 * and every snapshot is built directly from the scenario's spot_vix + option_chain data as of the
 * current sim clock. Active only under the {@code simulation} profile — the real
 * {@code LiveMarketDataService} (@Profile("!simulation")) serves production.
 */
@Service
@Profile("simulation")
public class SimulatedMarketDataService implements MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMarketDataService.class);

    private final ScenarioReader scenario;
    private final Clock clock;

    public SimulatedMarketDataService(ScenarioReader scenario, Clock clock) {
        this.scenario = scenario;
        this.clock = clock;
    }

    @Override
    public LiveMarketSnapshot fetchSnapshot(TradeLegDto shortLeg, TradeLegDto longLeg, LocalDate expiryDate) {
        return snapshot(shortLeg, longLeg);
    }

    @Override
    public Map<LocalDate, List<UpstoxOptionChainRow>> batchFetchChains(Set<LocalDate> expiryDates) {
        // The simulated snapshot reads the scenario folder directly; no Upstox chain rows are built.
        return Map.of();
    }

    @Override
    public LiveMarketSnapshot buildSnapshotFromChain(List<UpstoxOptionChainRow> chain, BigDecimal vix,
                                                     TradeLegDto shortLeg, TradeLegDto longLeg) {
        return snapshot(shortLeg, longLeg);   // chain + vix args ignored — the folder is the source
    }

    @Override
    public BigDecimal fetchVix() {
        return scenario.spotVixAt(clock.instant()).map(ScenarioData.SpotVix::vix).orElse(null);
    }

    /** Builds a snapshot for the PE (or 2-leg) short/long legs from the scenario at the sim-clock time. */
    private LiveMarketSnapshot snapshot(TradeLegDto shortLeg, TradeLegDto longLeg) {
        Instant now = clock.instant();

        Optional<ScenarioData.SpotVix> sv = scenario.spotVixAt(now);
        BigDecimal spot = sv.map(ScenarioData.SpotVix::spot).orElse(null);
        BigDecimal vix  = sv.map(ScenarioData.SpotVix::vix).orElse(null);

        Optional<ScenarioData.Strike> shortRow = scenario.strikeAt(now, shortLeg.strike(), shortLeg.optionType());
        Optional<ScenarioData.Strike> longRow  = scenario.strikeAt(now, longLeg.strike(),  longLeg.optionType());

        BigDecimal shortLtp = shortRow.map(ScenarioData.Strike::ltp).orElse(null);
        BigDecimal longLtp  = longRow.map(ScenarioData.Strike::ltp).orElse(null);
        BigDecimal shortIv  = shortRow.map(ScenarioData.Strike::iv).orElse(null);

        log.debug("sim.market.snapshot at={} spot={} vix={} shortLtp={} longLtp={} shortIv={}",
                now, spot, vix, shortLtp, longLtp, shortIv);
        return new LiveMarketSnapshot(spot, vix, shortLtp, longLtp, shortIv);
    }
}
