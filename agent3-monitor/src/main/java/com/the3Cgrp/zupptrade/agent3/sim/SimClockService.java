package com.the3Cgrp.zupptrade.agent3.sim;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the virtual clock's current instant, persisted to the singleton {@code sim_clock}
 * row (V113). Reads are served from an in-memory {@link AtomicReference} because
 * {@code now()} is called many times per evaluation; {@code set}/{@code advance} update
 * both memory and the DB row (durable, and visible to other processes that re-read).
 *
 * Active ONLY under the {@code simulation} profile — never present in production.
 */
@Service
@Profile("simulation")
public class SimClockService {

    private static final Logger log = LoggerFactory.getLogger(SimClockService.class);

    private final JdbcTemplate jdbc;
    private final AtomicReference<Instant> now = new AtomicReference<>();

    public SimClockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        // Ensure the singleton row exists (idempotent), then load whatever is stored —
        // which may be a value a previous run or another process already set.
        jdbc.update("INSERT INTO sim_clock (id, current_ts) VALUES (1, ?) ON CONFLICT (id) DO NOTHING",
                OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        now.set(readRow());
        log.warn("======================================================================");
        log.warn(" SIMULATION CLOCK ACTIVE — virtual time in effect (now={})", now.get());
        log.warn(" The 'simulation' profile MUST NEVER be enabled in production.");
        log.warn("======================================================================");
    }

    /** Current virtual instant (served from memory; authoritative for this process). */
    public Instant current() {
        return now.get();
    }

    /** Set the virtual clock to an absolute instant. Persisted and returned. */
    public Instant set(Instant at) {
        persist(at);
        now.set(at);
        log.info("sim.clock.set to={}", at);
        return at;
    }

    /** Advance the virtual clock by a duration. Persisted and returned. */
    public Instant advance(Duration by) {
        return set(now.get().plus(by));
    }

    private Instant readRow() {
        return jdbc.queryForObject(
                "SELECT current_ts FROM sim_clock WHERE id = 1",
                (rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant());
    }

    private void persist(Instant at) {
        jdbc.update("UPDATE sim_clock SET current_ts = ?, updated_at = NOW() WHERE id = 1",
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }
}
