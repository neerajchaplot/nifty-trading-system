package com.the3Cgrp.zupptrade.agent3.sim;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} whose "now" is the virtual instant held in {@code sim_clock} and
 * advanced by the conductor. Instantiated only under the {@code simulation} profile
 * (see {@link SimClockConfig}), so every business call site that uses {@code now(clock)}
 * or {@code clock.instant()} transparently runs on virtual time with zero code changes.
 *
 * Immutable and thread-safe: state lives in {@link SimClockService}; this is a thin view.
 */
public final class SimClock extends Clock {

    private final SimClockService state;
    private final ZoneId zone;

    public SimClock(SimClockService state, ZoneId zone) {
        this.state = state;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new SimClock(state, newZone);
    }

    @Override
    public Instant instant() {
        return state.current();
    }
}
