package com.the3Cgrp.zupptrade.agent3.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the scheduler timezone bug.
 *
 * The container runs UTC, but the cron windows (9-15) and isMarketHours() are IST.
 * Without an explicit IST zone on @Scheduled, the cron fires in UTC and only overlaps
 * the IST market-hours gate for the final hour of the trading day — so live P&L never
 * populates in production. Both scheduled crons MUST declare an IST zone.
 */
class SchedulerZoneTest {

    @Test
    void monitoringCycle_declaresIstZone() throws Exception {
        assertIstZone(MonitorSchedulerService.class.getDeclaredMethod("runMonitoringCycle"));
    }

    @Test
    void expiryPnlSweep_declaresIstZone() throws Exception {
        assertIstZone(ExpiryPnlService.class.getDeclaredMethod("sweepExpiredTrades"));
    }

    private void assertIstZone(Method method) {
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("@Scheduled present on %s", method.getName()).isNotNull();
        // zone may be a literal or a ${...} placeholder — either way it must pin Asia/Kolkata.
        assertThat(scheduled.zone())
                .as("@Scheduled.zone must be set to IST on %s", method.getName())
                .isNotBlank()
                .contains("Asia/Kolkata");
    }
}
