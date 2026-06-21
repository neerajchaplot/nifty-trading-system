-- ============================================================
-- V4 — Extend trades table with trade_code and calculation_audit
--
-- trade_code  : Human-readable broker tag in format T-YYYYMMDD-XXXX.
--               Used as the Zerodha order tag (≤ 20 chars).
--               Generated at trade-creation time via trade_code_seq.
--               Example: T-20250603-0001
--
-- calculation_audit : Snapshot of every intermediate value used to
--               generate this recommendation (HV, IV, regime, scoring
--               weights, gate results). Enables exact backtesting
--               replay without re-running Agent 1 or Agent 2.
-- ============================================================

-- Sequence that drives the XXXX suffix — global (no daily reset).
-- Max value 9999 → 4-digit zero-padded number. Cycle to 1 after 9999
-- trades in a single day is not a realistic concern.
CREATE SEQUENCE IF NOT EXISTS trade_code_seq
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;

-- Add trade_code as nullable first so the migration is safe even if
-- the trades table already contains rows (e.g. dev / QA environments).
ALTER TABLE trades ADD COLUMN IF NOT EXISTS trade_code VARCHAR(20);

-- Backfill any pre-existing rows with a deterministic code derived
-- from their created_at timestamp and the sequence.
UPDATE trades
SET trade_code = 'T-' || TO_CHAR(created_at, 'YYYYMMDD') || '-' ||
                  LPAD(nextval('trade_code_seq')::TEXT, 4, '0')
WHERE trade_code IS NULL;

-- Now that every row has a value, enforce NOT NULL and UNIQUE.
ALTER TABLE trades ALTER COLUMN trade_code SET NOT NULL;
ALTER TABLE trades ADD CONSTRAINT uq_trades_trade_code UNIQUE (trade_code);

-- Audit blob: stores every intermediate calculation value that
-- contributed to this trade card (scoring weights, thresholds,
-- HV, IV regime, gate verdicts). Optional on creation; populated
-- by RecommendationService before persisting.
ALTER TABLE trades ADD COLUMN IF NOT EXISTS calculation_audit JSONB;

-- Index for quick lookup by trade_code (used when syncing Zerodha tags).
CREATE INDEX IF NOT EXISTS idx_trades_trade_code ON trades (trade_code);
