-- V9: notifications table for user alerts + shedlock for scheduler de-duplication.

-- All agents write to this table. Orchestrator (or future UI) reads unacknowledged rows
-- and delivers them via configured channel (email, Telegram, etc.).
-- trade_id is nullable — some alerts (e.g. system-level VIX spike) are not trade-specific.
CREATE TABLE notifications (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_id        UUID,
    type            VARCHAR(20) NOT NULL CHECK (type IN ('CRITICAL', 'WARNING', 'INFO')),
    event           VARCHAR(100) NOT NULL,    -- exit_failed, slippage_alert, external_close, etc.
    message         TEXT        NOT NULL,
    acknowledged_at TIMESTAMPTZ,             -- NULL = unread
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_trade_id ON notifications(trade_id) WHERE trade_id IS NOT NULL;
-- Efficient unread fetch for the UI / Orchestrator
CREATE INDEX idx_notifications_unread   ON notifications(created_at DESC)
    WHERE acknowledged_at IS NULL;

-- ShedLock — standard schema required by the net.javacrumbs.shedlock library.
-- Agent 3 uses this to prevent two instances from running the 5-minute monitoring
-- cycle simultaneously (e.g. during a rolling deploy or pod restart).
-- TODO(task#19-followup): Add @SchedulerLock to MonitorSchedulerService.runMonitoringCycle()
--   once this migration has been applied.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
