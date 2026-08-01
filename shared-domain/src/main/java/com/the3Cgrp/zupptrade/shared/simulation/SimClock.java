package com.the3Cgrp.zupptrade.shared.simulation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} backed by the shared {@link SimClockStore} (the {@code sim_clock} row).
 * Plain Java so every agent module reuses it under the simulation profile: business code that
 * calls {@code now(clock)} / {@code clock.instant()} transparently runs on the shared virtual time.
 */
public final class SimClock extends Clock {

    private final SimClockStore store;
    private final ZoneId zone;

    public SimClock(SimClockStore store, ZoneId zone) {
        this.store = store;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new SimClock(store, newZone);
    }

    @Override
    public Instant instant() {
        return store.current();
    }
}
