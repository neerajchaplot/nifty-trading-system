package com.the3Cgrp.zupptrade.agent3.startup;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.service.ExpiryPnlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the expiry P&L sweep once at application startup.
 *
 * <p><b>Why:</b> {@link ExpiryPnlService#sweepExpiredTrades()} is scheduled at 8:30 AM IST. If agent3
 * is not running at exactly that time (crash, redeploy, maintenance window), the cron simply does not
 * fire and any expired-but-still-ACTIVE trade lingers until the <i>next</i> 8:30 AM. Running the sweep
 * on startup means a restart settles those trades immediately and they flow through to Audit.
 *
 * <p>The call goes through the Spring proxy, so the same {@code @SchedulerLock} (ShedLock) that guards
 * the scheduled run also guards this one — two instances starting together cannot double-settle.
 *
 * <p>Any failure is logged and swallowed: a sweep problem (e.g. Upstox candle API down) must never
 * prevent the monitor from starting. The next scheduled sweep, or the next restart, retries.
 *
 * <p>Disable via {@code agent3.monitoring.sweep-on-startup: false}.
 */
@Component
public class ExpirySweepStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweepStartupRunner.class);

    private final ExpiryPnlService expiryPnlService;
    private final MonitoringProperties props;

    public ExpirySweepStartupRunner(ExpiryPnlService expiryPnlService, MonitoringProperties props) {
        this.expiryPnlService = expiryPnlService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isSweepOnStartup()) {
            log.info("agent3.expiry_sweep.startup_disabled");
            return;
        }
        log.info("agent3.expiry_sweep.startup_trigger");
        try {
            expiryPnlService.sweepExpiredTrades();
        } catch (Exception e) {
            // Never let a startup sweep failure block the monitor from coming up.
            log.error("agent3.expiry_sweep.startup_error error={}", e.getMessage(), e);
        }
    }
}
