package com.the3Cgrp.zupptrade.agent3.startup;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.service.ExpiryPnlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Verifies the startup sweep: on boot, agent3 settles lingering expired trades immediately instead of
 * waiting for the next 8:30 AM cron — and a sweep failure never blocks startup.
 */
@ExtendWith(MockitoExtension.class)
class ExpirySweepStartupRunnerTest {

    @Mock private ExpiryPnlService expiryPnlService;

    @Test
    void runsSweepOnStartup_whenEnabled() {
        MonitoringProperties props = new MonitoringProperties(); // sweepOnStartup defaults true
        ExpirySweepStartupRunner runner = new ExpirySweepStartupRunner(expiryPnlService, props);

        runner.run(null);

        verify(expiryPnlService).sweepExpiredTrades();
    }

    @Test
    void skipsSweep_whenDisabled() {
        MonitoringProperties props = new MonitoringProperties();
        props.setSweepOnStartup(false);
        ExpirySweepStartupRunner runner = new ExpirySweepStartupRunner(expiryPnlService, props);

        runner.run(null);

        verify(expiryPnlService, never()).sweepExpiredTrades();
    }

    @Test
    void swallowsException_soStartupIsNeverBlocked() {
        MonitoringProperties props = new MonitoringProperties();
        doThrow(new RuntimeException("Upstox candle API down")).when(expiryPnlService).sweepExpiredTrades();
        ExpirySweepStartupRunner runner = new ExpirySweepStartupRunner(expiryPnlService, props);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        verify(expiryPnlService).sweepExpiredTrades();
    }
}
