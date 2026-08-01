package com.the3Cgrp.zupptrade.agent2.client;

import com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData;
import com.the3Cgrp.zupptrade.agent2.client.model.StrikeData;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioData;
import com.the3Cgrp.zupptrade.shared.simulation.ScenarioReader;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Simulation option chain — builds an {@link OptionChainData} from the scenario folder's
 * option_chain.csv at the fixed entry instant. Replaces {@code UpstoxOptionChainClient} under the
 * {@code simulation} profile. Instrument keys are synthesized ("SIM|…") — Agent 5 runs offline
 * (simulate-fills) and Agent 3 monitoring keys legs by strike/type, so a real key isn't needed.
 */
@Component
@Profile("simulation")
public class SimulatedOptionChainClient implements OptionChainClient {

    private final ScenarioReader scenario;
    private final Clock clock;

    public SimulatedOptionChainClient(ScenarioReader scenario, Clock clock) {
        this.scenario = scenario;
        this.clock = clock;
    }

    @Override
    public OptionChainData fetch(LocalDate expiryDate) {
        Instant t = clock.instant();
        List<ScenarioData.Strike> rows = scenario.optionChainAt(t);

        List<StrikeData> calls = rows.stream()
                .filter(s -> s.type() == OptionType.CE)
                .map(s -> toStrike(s, expiryDate)).toList();
        List<StrikeData> puts = rows.stream()
                .filter(s -> s.type() == OptionType.PE)
                .map(s -> toStrike(s, expiryDate)).toList();

        BigDecimal spot = scenario.spotVixAt(t).map(ScenarioData.SpotVix::spot).orElse(BigDecimal.ZERO);
        int atm = (int) (Math.round(spot.doubleValue() / 50.0) * 50);

        return new OptionChainData(spot, expiryDate, calls, puts, atm, ltpAt(calls, atm), ltpAt(puts, atm));
    }

    private StrikeData toStrike(ScenarioData.Strike s, LocalDate expiry) {
        return new StrikeData(
                s.strike(), s.type(), s.ltp(), s.iv(), s.delta(), s.pop(),
                BigDecimal.valueOf(s.oi()), null, null,
                "SIM|NIFTY|" + expiry + "|" + s.strike() + "|" + s.type());
    }

    private static BigDecimal ltpAt(List<StrikeData> legs, int strike) {
        return legs.stream().filter(s -> s.strike() == strike).map(StrikeData::ltp).findFirst().orElse(null);
    }
}
