-- Critical alerts: situations the system could NOT resolve automatically and that require the
-- end user to act (e.g. an ambiguous order-placement failure where a position may be open on the
-- exchange beyond our control). Distinct from the notifications table (routine user alerts):
-- a critical_alert always demands manual review/action and carries a transparent JSON snapshot
-- of the trade state as the system knew it at the time.
CREATE TABLE critical_alerts (
    alert_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_id        UUID,                                    -- nullable: alert may precede/lack a trade
    alert_reason    TEXT NOT NULL,                           -- what happened + what the user must check
    trade_details   JSONB NOT NULL,                          -- transparent snapshot of trade state
    status          VARCHAR(20) NOT NULL DEFAULT 'LIVE',     -- LIVE | ACKNOWLEDGED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged_at TIMESTAMPTZ
);

-- UI critical-alert card queries LIVE alerts; Agent 4 lists ACKNOWLEDGED alerts.
CREATE INDEX idx_critical_alerts_status ON critical_alerts (status);
CREATE INDEX idx_critical_alerts_trade  ON critical_alerts (trade_id);
