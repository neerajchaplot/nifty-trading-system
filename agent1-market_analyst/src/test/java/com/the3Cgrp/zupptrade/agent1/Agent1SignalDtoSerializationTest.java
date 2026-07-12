package com.the3Cgrp.zupptrade.agent1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.the3Cgrp.zupptrade.shared.dto.Agent1SignalDto;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.Confidence;
import com.the3Cgrp.zupptrade.shared.enums.Strength;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the "signal age all over the place" + "Nifty 50 shows —" defects.
 *
 * <p>The signal timestamp used to be a zoneless {@code LocalDateTime}. On the UTC
 * container it serialised without any zone, and IST browsers read it as local time —
 * inflating the computed age by ~5.5h. The fix makes the timestamp a zone-aware
 * {@link OffsetDateTime} (serialised with an offset), so any client computes age
 * correctly regardless of its own timezone. Spot is now surfaced too.
 */
class Agent1SignalDtoSerializationTest {

    // findAndRegisterModules() discovers the JavaTime module on the runtime classpath
    // (present transitively via Spring Boot) without a compile-time dependency on jsr310.
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // matches Spring Boot default

    private final TimeZone originalTz = TimeZone.getDefault();

    @AfterEach
    void restoreTz() {
        TimeZone.setDefault(originalTz);
    }

    private Agent1SignalDto sampleSignal() {
        return new Agent1SignalDto(
                UUID.randomUUID(),
                OffsetDateTime.of(2026, 7, 12, 7, 5, 0, 0, ZoneOffset.UTC),
                LocalDate.of(2026, 7, 14),
                Bias.BULLISH, Strength.WEAK,
                new BigDecimal("0.1590"), new BigDecimal("0.55"), Confidence.MEDIUM,
                new BigDecimal("12.25"), VixRegime.LOW, "Falling",
                "{}", false, null, "[]",
                new BigDecimal("23412.60"));
    }

    @Test
    void timestampSerialisesWithUtcOffset() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sampleSignal()));

        // Zone-qualified — the exact property that was missing and caused the age bug.
        assertThat(json.get("timestamp").asText()).isEqualTo("2026-07-12T07:05:00Z");
    }

    @Test
    void timestampIsTimezoneIndependent() throws Exception {
        // Serialise the same instant under a non-UTC default zone (as the IST dev box does).
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        String istRun = mapper.writeValueAsString(sampleSignal());

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String utcRun = mapper.writeValueAsString(sampleSignal());

        // Output must be identical regardless of JVM default zone.
        assertThat(istRun).isEqualTo(utcRun);
        assertThat(mapper.readTree(istRun).get("timestamp").asText()).endsWith("Z");
    }

    @Test
    void spotIsSurfacedForTheNiftyStrip() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sampleSignal()));

        assertThat(json.hasNonNull("spot")).isTrue();
        assertThat(new BigDecimal(json.get("spot").asText())).isEqualByComparingTo("23412.60");
    }
}
