package com.the3Cgrp.zupptrade.agent3.sim;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Under the {@code simulation} profile, replaces the production system {@link Clock}
 * (see {@code ClockConfig}) with a {@link SimClock} backed by {@code sim_clock}.
 *
 * Same bean type/name as the production clock, so every {@code now(clock)} call site
 * transparently switches to virtual time — no business code changes, only this bean is
 * swapped. The zone stays the exchange zone (default Asia/Kolkata) so virtual
 * {@code LocalDate}/{@code LocalTime} derivations remain IST-consistent, exactly as prod.
 */
@Configuration
@Profile("simulation")
public class SimClockConfig {

    @Bean
    public Clock clock(SimClockService state,
                       @Value("${agent3.clock.zone:Asia/Kolkata}") String zone) {
        return new SimClock(state, ZoneId.of(zone));
    }
}
