-- Agent 3 monitoring evaluations — one row per 5-minute evaluation cycle per active trade.
-- Agent 3 does NOT write to trade_ledger; Agent 5 writes ledger events on execution.
-- trade_id references trades(id) logically but no FK — Agent 3 is a separate deployable.

CREATE TABLE monitoring_evaluations (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_id             UUID        NOT NULL,
    evaluated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    action               VARCHAR(20) NOT NULL,      -- HOLD, WATCH, READJUST, EXIT, PAUSE
    reason               TEXT        NOT NULL,      -- human-readable explanation with key metrics
    threshold_hit        VARCHAR(50),               -- which threshold triggered the action
    spot_price           DECIMAL(10,2),
    vix_level            DECIMAL(6,2),
    current_pop          DECIMAL(8,6),              -- live Black-Scholes PoP (0.0–1.0)
    current_net_premium  DECIMAL(10,4),             -- current cost/value to close spread
    mark_to_market_pnl   DECIMAL(12,2),             -- unrealised P&L in Rs (negative = loss)
    short_leg_ltp        DECIMAL(10,2),
    long_leg_ltp         DECIMAL(10,2),
    evaluation_detail    JSONB,                     -- full calculation breakdown for audit
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_monitoring_evaluations_trade_id     ON monitoring_evaluations(trade_id);
CREATE INDEX idx_monitoring_evaluations_evaluated_at ON monitoring_evaluations(evaluated_at DESC);
-- Fetch latest evaluation per trade efficiently
CREATE INDEX idx_monitoring_evaluations_trade_time   ON monitoring_evaluations(trade_id, evaluated_at DESC);
