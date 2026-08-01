package com.the3Cgrp.zupptrade.agent3.sim;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Control surface for the virtual clock — the conductor uses this to step time so a
 * multi-day scenario runs in seconds. Active ONLY under the {@code simulation} profile,
 * so it can never be reached in production.
 *
 * <pre>
 *   GET  /sim/clock                     -> current virtual instant
 *   POST /sim/clock/set     {"at": ...} -> set to an ISO-8601 offset-datetime or instant
 *   POST /sim/clock/advance {"seconds"} -> advance by N seconds
 * </pre>
 */
@RestController
@RequestMapping("/sim/clock")
@Profile("simulation")
public class SimClockController {

    private final SimClockService clock;

    public SimClockController(SimClockService clock) {
        this.clock = clock;
    }

    public record SetRequest(String at) {}
    public record AdvanceRequest(Long seconds) {}

    @GetMapping
    public Map<String, String> current() {
        return Map.of("current", clock.current().toString());
    }

    @PostMapping("/set")
    public Map<String, String> set(@RequestBody(required = false) SetRequest req) {
        if (req == null || req.at() == null || req.at().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'at' is required (ISO-8601)");
        }
        return Map.of("current", clock.set(parse(req.at())).toString());
    }

    @PostMapping("/advance")
    public Map<String, String> advance(@RequestBody(required = false) AdvanceRequest req) {
        if (req == null || req.seconds() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'seconds' is required");
        }
        return Map.of("current", clock.advance(Duration.ofSeconds(req.seconds())).toString());
    }

    /** Accepts an offset date-time ("2026-07-13T13:00:00+05:30") or a UTC instant ("...Z"). */
    private Instant parse(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException offsetFailed) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException instantFailed) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "'at' must be ISO-8601 offset-datetime or instant, e.g. 2026-07-13T13:00:00+05:30");
            }
        }
    }
}
