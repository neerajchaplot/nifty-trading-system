package com.the3Cgrp.zupptrade.agent3.sim;

import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5 contract test — the drift tripwire for golden fixtures.
 *
 * A Phase-B golden fixture ({@code test-data/simulation/fixtures/golden/*.json}) is only
 * trustworthy if its {@code monitor_config} matches what Agent 2 actually produces — i.e. the
 * current {@link MonitorConfigDto} shape that Agent 3 deserializes and reads. This test fails
 * if the two drift apart, forcing a re-capture (via {@code capture_golden.sh}).
 *
 * The core tripwire is reflective: every non-optional record component of {@code MonitorConfigDto}
 * must be present (non-null) in the fixture. So if the engine ADDS a field, an old fixture that
 * lacks it fails here — exactly the stale-seed failure mode (test-matrix F2) we must never repeat.
 *
 * Runs fully offline — no Spring context, no DB, no Upstox. Uses the project's Jackson 3 mapper
 * ({@code JsonMapper.builder().build()}), mirroring production deserialization.
 */
class GoldenFixtureContractTest {

    /** Fields that are legitimately null/absent for some strategies — excluded from the presence check. */
    private static final Set<String> OPTIONAL_COMPONENTS = Set.of("shortLeg2", "longLeg2", "slippageAmount");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void everyGoldenFixtureMatchesTheMonitorConfigDtoShape() throws Exception {
        Path dir = resolveGoldenDir();
        assertThat(Files.isDirectory(dir))
                .as("golden fixtures directory not found at %s", dir.toAbsolutePath())
                .isTrue();

        List<Path> fixtures;
        try (Stream<Path> s = Files.list(dir)) {
            fixtures = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(fixtures).as("no golden fixtures found in %s", dir).isNotEmpty();

        for (Path fixture : fixtures) {
            validate(fixture);
        }
    }

    private void validate(Path fixture) throws Exception {
        String name = fixture.getFileName().toString();
        JsonNode root = mapper.readTree(Files.readString(fixture));

        JsonNode mcNode = root.get("monitor_config");
        assertThat(mcNode).as("%s: 'monitor_config' block missing", name).isNotNull();

        // Deserialize into the real DTO — this is exactly what Agent 3 does with a stored monitor_config.
        MonitorConfigDto config = mapper.readValue(mcNode.toString(), MonitorConfigDto.class);

        // ── DRIFT TRIPWIRE ────────────────────────────────────────────────────────
        // Every non-optional MonitorConfigDto component must be present in the fixture. If the
        // engine adds a component, an old fixture lacks it → deserializes null → fails here.
        for (RecordComponent rc : MonitorConfigDto.class.getRecordComponents()) {
            if (rc.getType().isPrimitive() || OPTIONAL_COMPONENTS.contains(rc.getName())) {
                continue;   // primitives are never null; optional components may legitimately be absent
            }
            Object value = rc.getAccessor().invoke(config);
            assertThat(value)
                    .as("%s: MonitorConfigDto.%s is null — the fixture is missing a field the engine "
                        + "now produces. Re-capture this golden fixture from a real Phase-A run "
                        + "(capture_golden.sh, contract §5).", name, rc.getName())
                    .isNotNull();
        }

        // ── Agent-3 / Agent-5 critical invariants ─────────────────────────────────
        assertThat(config.lots()).as("%s: lots must be > 0", name).isPositive();
        assertThat(config.lotSize()).as("%s: lotSize must be > 0", name).isPositive();
        assertLegComplete(name, "shortLeg", config.shortLeg());
        assertLegComplete(name, "longLeg", config.longLeg());

        boolean ironCondor = config.strategy() == Strategy.IRON_CONDOR
                || config.strategy() == Strategy.WIDE_IRON_CONDOR;
        int expectedLegs;
        if (ironCondor) {
            assertThat(config.shortLeg2()).as("%s: Iron Condor requires a CE short leg", name).isNotNull();
            assertThat(config.longLeg2()).as("%s: Iron Condor requires a CE long leg", name).isNotNull();
            assertLegComplete(name, "shortLeg2", config.shortLeg2());
            assertLegComplete(name, "longLeg2", config.longLeg2());
            assertThat(config.thresholds().t1WatchNiftyDown())
                    .as("%s: Iron Condor needs bilateral (down) thresholds", name).isNotNull();
            assertThat(config.thresholds().t1WatchNiftyUp())
                    .as("%s: Iron Condor needs bilateral (up) thresholds", name).isNotNull();
            expectedLegs = 4;
        } else {
            assertThat(config.shortLeg2()).as("%s: 2-leg spread must not carry a shortLeg2", name).isNull();
            expectedLegs = 2;
        }

        // ── entry_fills present, an array, one per leg ────────────────────────────
        JsonNode fills = root.get("entry_fills");
        assertThat(fills).as("%s: 'entry_fills' missing", name).isNotNull();
        assertThat(fills.isArray()).as("%s: 'entry_fills' must be a JSON array", name).isTrue();
        assertThat(fills.size()).as("%s: expected %d entry fills", name, expectedLegs).isEqualTo(expectedLegs);

        // ── Round-trip — no field silently lost through serialize → deserialize ───
        MonitorConfigDto roundTrip = mapper.readValue(mapper.writeValueAsString(config), MonitorConfigDto.class);
        assertThat(roundTrip).as("%s: monitor_config does not round-trip cleanly", name).isEqualTo(config);
    }

    private void assertLegComplete(String fixture, String legName, TradeLegDto leg) {
        assertThat(leg).as("%s: %s is null", fixture, legName).isNotNull();
        assertThat(leg.optionType()).as("%s: %s.optionType", fixture, legName).isNotNull();
        assertThat(leg.action()).as("%s: %s.action", fixture, legName).isNotNull();
        assertThat(leg.instrumentKey())
                .as("%s: %s.instrumentKey is null — Agent 5 needs it to place the exit order", fixture, legName)
                .isNotNull();
        assertThat(leg.strike()).as("%s: %s.strike must be > 0", fixture, legName).isPositive();
    }

    /** Resolve the golden dir whether tests run from the module dir (Maven) or the repo root (IDE). */
    private static Path resolveGoldenDir() {
        Path[] candidates = {
                Path.of("..", "test-data", "simulation", "fixtures", "golden"), // agent3-monitor module (Maven surefire)
                Path.of("test-data", "simulation", "fixtures", "golden")        // repo root (IDE working dir)
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p;
        }
        return candidates[0];
    }
}
