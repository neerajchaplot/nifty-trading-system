package com.the3Cgrp.zupptrade.agent3.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Provides the application {@link Clock}.
 *
 * Design pattern: Dependency Injection of {@code java.time.Clock} — the standard,
 * test-friendly seam for "what time is it". Business classes call {@code now(clock)}
 * and never see which clock they were handed. This lets a simulation profile later
 * substitute a virtual clock by defining its own {@code Clock} bean — no business
 * code changes, only this bean is swapped.
 *
 * <p>Production uses the real system clock in the exchange timezone (default
 * {@code Asia/Kolkata} — NSE). The container runs UTC, but Agent 3's DTE calculation,
 * signal staleness, market-hours gate and audit timestamps are all IST concerns;
 * pinning the clock to IST makes them consistent. During market hours
 * (09:15–15:30 IST = 03:45–10:00 UTC) the IST and UTC calendar dates are identical,
 * so this is behaviour-preserving for the previous bare {@code now()} calls.
 */
@Configuration
public class ClockConfig {

    /**
     * Real system clock in the exchange timezone.
     * {@code @ConditionalOnMissingBean} so a simulation/test profile can define its own
     * {@code Clock} bean (e.g. a settable virtual clock) that takes precedence without
     * any profile annotation here.
     *
     * @param zone IANA zone id, overridable via {@code agent3.clock.zone} (default Asia/Kolkata)
     */
    @Bean
    @Profile("!simulation")
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock(@Value("${agent3.clock.zone:Asia/Kolkata}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
