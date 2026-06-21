-- ============================================================
-- V7 — Daily P&L snapshots (one row per trade per calendar day)
--
-- Populated by an end-of-day sync job that:
--   1. Queries Zerodha's positions / P&L API using trade_code as tag
--   2. Writes or upserts one row per open trade
--
-- Design decisions:
--   - One snapshot per trade per day (UNIQUE(trade_id, snapshot_date))
--   - P&L sourced exclusively from Zerodha (avoids brokerage/tax
--     calculation complexity in our system)
--   - realised_pnl = closed legs only; unrealised_pnl = open legs mark-to-market
--   - total_pnl = realised_pnl + unrealised_pnl (stored for fast aggregation)
--   - position_status reflects broker's view on that snapshot_date:
--       OPEN             — all legs still open
--       PARTIALLY_CLOSED — some legs closed (e.g. after partial fill exit)
--       CLOSED           — all legs closed (realised_pnl = total_pnl)
--   - raw_broker_payload stores the unmodified Zerodha response for
--     reconciliation and backtest replay
--
-- Backtesting link:
--   JOIN trade_pnl p ON p.trade_id = t.id
--   JOIN trades t ON t.agent1_signal_id = s.id
--   → ties each P&L outcome back to the Agent 1 signal that triggered it
-- ============================================================

CREATE TABLE trade_pnl (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),

    trade_id          UUID           NOT NULL REFERENCES trades(id),

    -- Calendar date of this snapshot (not a trading timestamp).
    snapshot_date     DATE           NOT NULL,

    -- Amounts in ₹. Positive = profit.
    realised_pnl      DECIMAL(12,2),
    unrealised_pnl    DECIMAL(12,2),
    total_pnl         DECIMAL(12,2),

    -- Broker's position status on this snapshot_date.
    -- OPEN | PARTIALLY_CLOSED | CLOSED
    position_status   VARCHAR(20)    NOT NULL DEFAULT 'OPEN',

    -- When this snapshot was last written/updated from Zerodha.
    synced_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    -- Full unmodified Zerodha positions API response for this trade.
    -- Used for: reconciliation, audit, backtest replay.
    raw_broker_payload JSONB,

    -- Only one snapshot per trade per day.
    CONSTRAINT uq_trade_pnl_trade_date UNIQUE (trade_id, snapshot_date)
);

-- Fast retrieval of P&L history for a single trade.
CREATE INDEX idx_trade_pnl_trade_id
    ON trade_pnl (trade_id, snapshot_date DESC);

-- Aggregate daily P&L across all trades (portfolio-level dashboard query).
CREATE INDEX idx_trade_pnl_snapshot_date
    ON trade_pnl (snapshot_date DESC);

-- Find all open positions needing EOD P&L sync.
CREATE INDEX idx_trade_pnl_open_positions
    ON trade_pnl (position_status, snapshot_date DESC)
    WHERE position_status IN ('OPEN', 'PARTIALLY_CLOSED');
