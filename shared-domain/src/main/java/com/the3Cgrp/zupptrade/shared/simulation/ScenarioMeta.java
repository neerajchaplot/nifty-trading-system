package com.the3Cgrp.zupptrade.shared.simulation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scenario spine (scenario.yaml), parsed with plain Java — no Spring, no YAML library — so
 * every agent module can read it. Agents 1 and 2 use {@link #entryAt()} as their fixed "as-of"
 * time (no ticking clock); the conductor uses the clock window for Agent 3.
 *
 * Expected shape (only these fields are read):
 * <pre>
 *   name: bull_put_5day
 *   clock: { start: ..., end: ..., step: ... }
 *   entry:
 *     at: 2026-07-08T09:20:00+05:30
 *     userProfile: TEST_USER_10L
 *     expiry: 2026-07-14
 * </pre>
 */
public final class ScenarioMeta {

    private final String name;
    private final Instant entryAt;
    private final LocalDate expiry;
    private final String userProfile;

    private ScenarioMeta(String name, Instant entryAt, LocalDate expiry, String userProfile) {
        this.name = name;
        this.entryAt = entryAt;
        this.expiry = expiry;
        this.userProfile = userProfile;
    }

    public String name()        { return name; }
    public Instant entryAt()    { return entryAt; }
    public LocalDate expiry()   { return expiry; }
    public String userProfile() { return userProfile; }

    public static ScenarioMeta load(Path scenarioDir) {
        Path yaml = scenarioDir.resolve("scenario.yaml");
        if (!Files.isRegularFile(yaml)) {
            throw new IllegalArgumentException("scenario.yaml not found in " + scenarioDir.toAbsolutePath());
        }
        String text;
        try {
            text = Files.readString(yaml);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + yaml, e);
        }
        String name    = first(text, "name");
        String atRaw   = required(text, "at", yaml);
        String expRaw  = required(text, "expiry", yaml);
        String profile = first(text, "userProfile");
        return new ScenarioMeta(
                name,
                OffsetDateTime.parse(atRaw).toInstant(),
                LocalDate.parse(expRaw),
                profile);
    }

    /** First value of {@code key: value} (ignores comments), or null if absent. */
    private static String first(String text, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":\\s*(\\S+)\\s*$").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String required(String text, String key, Path yaml) {
        String v = first(text, key);
        if (v == null) {
            throw new IllegalStateException("scenario.yaml (" + yaml + ") is missing required '" + key + "'");
        }
        return v;
    }
}
