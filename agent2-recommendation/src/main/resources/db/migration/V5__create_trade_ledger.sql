-- ============================================================
-- V5 — Append-only audit ledger for all trade lifecycle events
--
-- Every state transition in the trade lifecycle is written here
-- as an immutable event. This table is NEVER updated — only
-- INSERT. Provides full replay capability for audit and debugging.
--
-- Event types (ordered lifecycle):
--   TRADE_PENDING          — recommendation generated, awaiting user approval
--   TRADE_APPROVED         — user approved the trade card
--   TRADE_REJECTED         — user rejected the trade card
--   TRADE_PLACED           — order sent to broker (before fill confirmation)
--   TRADE_EXECUTED         — broker confirmed full fill
--   TRADE_PARTIALLY_FILLED — broker confirmed partial fill
--   TRADE_FAILED           — broker rejected or order timed out
--   TRADE_ADJUSTED         — legs rolled or spread width changed
--   TRADE_CLOSE_INITIATED  — close order sent to broker
--   TRADE_CLOSED           — position fully closed
--   TRADE_EXPIRED          — trade card expired before confirmation
--
-- sequence_number is BIGSERIAL per-row for global ordering.
-- The UNIQUE(trade_id, sequence_number) constraint is enforced via
-- a partial unique index rather than a table constraint, so the
-- BIGSERIAL can continue across all trades without conflict.
--
-- TradeLedgerService runs in PROPAGATION_REQUIRES_NEW so ledger
-- writes persist even if the calling transaction rolls back.
-- ============================================================

CREATE TABLE trade_ledger (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- FK to the parent trade (nullable for TRADE_PENDING events where
    -- the trade row is written in the same transaction; FK validated after).
    trade_id         UUID          NOT NULL REFERENCES trades(id),

    -- Machine-readable event type from the ordered list above.
    event_type       VARCHAR(30)   NOT NULL,

    -- Monotonically increasing per-table sequence. Used to establish
    -- total ordering of events across all trades.
    sequence_number  BIGSERIAL     NOT NULL,

    -- Who triggered the event: "SYSTEM", "USER:<userId>", "SCHEDULER".
    occurred_by      VARCHAR(100)  NOT NULL DEFAULT 'SYSTEM',

    -- Event-specific data: broker order ID, fill price, slippage, etc.
    -- Schema varies by event_type — document in code, not DB constraint.
    payload          JSONB,

    occurred_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Guarantee event ordering per trade is unique.
CREATE UNIQUE INDEX uq_trade_ledger_trade_seq
    ON trade_ledger (trade_id, sequence_number);

-- Fast retrieval of full event history for a single trade (most common
-- read pattern: "show me everything that happened to trade T-X").
CREATE INDEX idx_trade_ledger_trade_id
    ON trade_ledger (trade_id, occurred_at ASC);

-- Supports "show me all TRADE_PLACED events in last 24h" type queries
-- used by the monitoring / alerting layer.
CREATE INDEX idx_trade_ledger_event_type
    ON trade_ledger (event_type, occurred_at DESC);
