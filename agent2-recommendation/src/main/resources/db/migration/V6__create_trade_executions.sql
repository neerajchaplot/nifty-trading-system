-- ============================================================
-- V6 — Mutable operational record of each broker execution attempt
--
-- One row per broker interaction:
--   - Initial entry (execution_type = ENTRY)
--   - Each roll/adjustment (execution_type = ADJUSTMENT)
--   - Closing execution (execution_type = EXIT)
--
-- broker_status lifecycle:
--   PLACED             → order sent, waiting for fill confirmation
--   EXECUTED           → all requested lots filled
--   PARTIALLY_FILLED   → some lots filled; remainder expired or rejected
--   FAILED             → broker rejected or timeout before any fill
--
-- Partial fill handling rule (agreed):
--   If broker_status = PARTIALLY_FILLED, exit the partial position
--   within 2 minutes via a compensating EXIT execution. The monitoring
--   layer reads filled_lots vs requested_lots to trigger this logic.
--
-- requested_legs / filled_legs JSONB schema (array of leg objects):
--   [{
--     "instrument_key": "NSE_FO|...",
--     "option_type": "CE" | "PE",
--     "strike": 24500,
--     "action": "SELL" | "BUY",
--     "requested_lots": 1,
--     "filled_lots": 1,         -- filled_legs only
--     "requested_price": 85.50,
--     "filled_price": 85.25,    -- filled_legs only
--     "broker_order_id": "..."  -- filled_legs only
--   }]
--
-- broker_refs JSONB:
--   { "zerodha_order_ids": ["..."], "zerodha_tag": "T-20250603-0001" }
-- ============================================================

CREATE TABLE trade_executions (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),

    trade_id              UUID           NOT NULL REFERENCES trades(id),

    -- ENTRY | ADJUSTMENT | EXIT
    execution_type        VARCHAR(20)    NOT NULL,

    -- Current broker-confirmed status of this execution attempt.
    broker_status         VARCHAR(25)    NOT NULL DEFAULT 'PLACED',

    -- What we asked the broker to do (set at TRADE_PLACED time, immutable).
    requested_legs        JSONB          NOT NULL,

    -- What the broker actually filled (populated at TRADE_EXECUTED /
    -- PARTIALLY_FILLED time). NULL until broker confirms.
    filled_legs           JSONB,

    -- Zerodha order IDs and the tag used. Stored for reconciliation.
    broker_refs           JSONB,

    -- Premium amounts in ₹ per lot (net credit = positive for short spreads).
    requested_net_premium DECIMAL(12,2),
    actual_net_premium    DECIMAL(12,2),

    -- Lots requested vs actually filled.
    requested_lots        INT            NOT NULL,
    filled_lots           INT            NOT NULL DEFAULT 0,

    -- Lot size at the time of execution (may change on contract expiry).
    lot_size              INT            NOT NULL,

    -- actual_net_premium - requested_net_premium per lot (negative = worse fill).
    slippage_amount       DECIMAL(12,2),

    -- Populated when broker_status = FAILED. Human-readable reason from
    -- Zerodha rejection message or timeout handler.
    failure_reason        TEXT,

    -- Timestamps
    placed_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    executed_at           TIMESTAMPTZ,                          -- NULL until broker confirms
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- All executions for a trade (used for partial-fill check and P&L rollup).
CREATE INDEX idx_trade_exec_trade_id
    ON trade_executions (trade_id, placed_at DESC);

-- Monitoring: find all PLACED executions older than 2 min → trigger exit.
CREATE INDEX idx_trade_exec_status_placed
    ON trade_executions (broker_status, placed_at DESC)
    WHERE broker_status = 'PLACED';
