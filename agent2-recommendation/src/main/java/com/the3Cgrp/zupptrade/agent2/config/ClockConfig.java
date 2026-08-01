package com.the3Cgrp.zupptrade.agent2.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Production {@link Clock} for Agent 2 — real system time in the exchange zone. Under the
 * simulation profile this backs off ({@code @Profile("!simulation")}) and the fixed sim clock
 * from {@code sim.SimulationConfig} is used instead.
 */
@Configuration
public class ClockConfig {

    @Bean
    @Profile("!simulation")
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock(@Value("${sim.clock-zone:Asia/Kolkata}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
