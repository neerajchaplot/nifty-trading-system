-- ============================================================
-- V3 — FII/DII daily snapshots
--
-- One row per trading session per segment. Used for:
--   1. 5-day trend calculation in Tier 2 (InstitutionalFlowScorer)
--   2. Backtesting — replay historical FII/DII inputs to Agent 1
--
-- Segments stored:
--   NSE_FO|INDEX_FUTURES  — FII index futures net flow + long/short OI
--   NSE_FO|INDEX_OPTIONS  — FII index options net flow
--   NSE_EQ|CASH           — DII cash net flow
--
-- Source: Upstox /v2/market/fii and /v2/market/dii (7-day window fetch).
-- Populated by FiiDiiService on every Agent 1 scoring run.
-- Duplicate inserts are silently skipped via the unique constraint.
-- ============================================================

CREATE TABLE fii_dii_snapshots (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    trading_date          DATE           NOT NULL,
    segment               VARCHAR(30)    NOT NULL,       -- NSE_FO|INDEX_FUTURES etc.
    buy_amount            DECIMAL(15,2),                 -- ₹ Crore
    sell_amount           DECIMAL(15,2),                 -- ₹ Crore
    net_flow              DECIMAL(15,2),                 -- buy_amount - sell_amount (computed on insert)
    buy_contracts         BIGINT,
    sell_contracts        BIGINT,
    oi_contracts          BIGINT,
    oi_amount             DECIMAL(15,2),
    total_long_contracts  BIGINT,                        -- cumulative OI; populated for INDEX_FUTURES only
    total_short_contracts BIGINT,
    long_ratio            DECIMAL(6,4),                  -- total_long / (total_long + total_short)
    fetched_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_fii_dii_date_segment UNIQUE (trading_date, segment)
);

-- Fast lookup for 5-day trend query (most common read path)
CREATE INDEX idx_fii_dii_segment_date ON fii_dii_snapshots (segment, trading_date DESC);

-- Backtesting range queries: WHERE trading_date BETWEEN :from AND :to
CREATE INDEX idx_fii_dii_date        ON fii_dii_snapshots (trading_date DESC);
