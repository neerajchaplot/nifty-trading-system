package com.the3Cgrp.zupptrade.core.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AlertService — verifies that CRITICAL alerts fan out to BOTH the notifications
 * feed and the critical_alerts table (the user-actionable card), while warning/info do not.
 */
@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock private JdbcTemplate         jdbc;
    @Mock private CriticalAlertService criticalAlertService;

    @Test
    void critical_writesNotification_andRecordsCriticalAlert() {
        AlertService svc = new AlertService(jdbc, criticalAlertService);
        UUID tradeId = UUID.randomUUID();

        svc.critical(tradeId, "exit_failed", "position may be open — manual intervention required");

        // notifications feed
        verify(jdbc).update(contains("INSERT INTO notifications"),
                eq(tradeId), eq("CRITICAL"), eq("exit_failed"),
                eq("position may be open — manual intervention required"));
        // critical_alerts table (UI card / manual action) — every critical failure lands here
        verify(criticalAlertService).record(eq(tradeId),
                eq("position may be open — manual intervention required"), any());
    }

    @Test
    void warning_writesNotificationOnly_noCriticalAlert() {
        AlertService svc = new AlertService(jdbc, criticalAlertService);

        svc.warning(UUID.randomUUID(), "slippage", "actual net below expected");

        verifyNoInteractions(criticalAlertService);
    }

    @Test
    void info_writesNotificationOnly_noCriticalAlert() {
        AlertService svc = new AlertService(jdbc, criticalAlertService);

        svc.info(UUID.randomUUID(), "readjust_success", "new trade active");

        verifyNoInteractions(criticalAlertService);
    }
}
