package com.the3Cgrp.zupptrade.shared.simulation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Reads/writes the singleton {@code sim_clock} row (V113) using plain JDBC — no Spring — so every
 * agent (all depend on shared-domain) can share ONE virtual clock across processes.
 *
 * Reads go straight to the row (no in-memory cache) so all agent processes see the same virtual
 * time the conductor set. Unqualified table name relies on the connection's search_path
 * (the same way the agents access every other table).
 */
public final class SimClockStore {

    private final DataSource dataSource;

    public SimClockStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Current virtual instant. Falls back to real time if the row is somehow absent. */
    public Instant current() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT current_ts FROM sim_clock WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                OffsetDateTime odt = rs.getObject(1, OffsetDateTime.class);
                if (odt != null) return odt.toInstant();
            }
            return Instant.now();
        } catch (SQLException e) {
            throw new IllegalStateException("sim_clock read failed", e);
        }
    }

    /** Set the virtual clock (upsert the singleton row). Returns the instant set. */
    public Instant set(Instant at) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO sim_clock (id, current_ts) VALUES (1, ?) " +
                     "ON CONFLICT (id) DO UPDATE SET current_ts = EXCLUDED.current_ts, updated_at = NOW()")) {
            ps.setObject(1, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            ps.executeUpdate();
            return at;
        } catch (SQLException e) {
            throw new IllegalStateException("sim_clock write failed", e);
        }
    }
}
