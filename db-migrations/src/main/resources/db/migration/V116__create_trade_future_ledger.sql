-- ============================================================
-- V116 — Nifty FUTURES intraday plan-of-record (single evolving row)
--
-- One row per Agent 2 futures /recommend run. Unlike the options
-- flow (trades + append-only trade_ledger), the futures paper-trade
-- phase keeps everything in ONE table whose `status` advances in
-- place. This row IS the §6.1 signal logger: it captures every run,
-- the chosen arm, the final GTT-ready levels, and (later) the fill
-- and outcome — the single artefact Agent 4 reports on.
--
-- The full decision package is computed once at recommend time so
-- the user approves once; Agent 3 only watches the entry trigger and
-- Agent 5's GTT (ENTRY+TARGET+STOPLOSS OCO) handles the rest.
--
-- Status lifecycle (see FuturePlanStatus enum):
--   PRIMED → ARMED → BREAK_DETECTED → CONFIRMED → FILLED → CLOSED
--   plus terminals: NO_TRADE, REJECTED, INVALIDATED, EXPIRED
-- ============================================================

CREATE TABLE trade_future_ledger (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Human-friendly code, e.g. FUT-20260802-001 (assigned in service layer).
    plan_code          VARCHAR(24)   UNIQUE NOT NULL,

    -- Source Agent 1 signal this plan was built from (bias + confidence).
    agent1_signal_id   UUID          REFERENCES agent1_signals(id),

    -- Owning user profile (position sizing / kill-switch scope).
    user_profile_id    UUID          NOT NULL REFERENCES user_profiles(id),

    -- 900 = 09:00 primed plan, 931 = 09:31 confirm run (spec §0). Parked but stored.
    run_phase          SMALLINT      NOT NULL DEFAULT 900,

    -- Resolved current-month Nifty futures instrument key, e.g. "NSE_FO|<token>".
    instrument_key     VARCHAR(64),

    trade_date         DATE          NOT NULL,

    -- Snapshot of Agent 1 direction at build time (denormalised for reporting + hover).
    bias               VARCHAR(10)   NOT NULL,   -- BULLISH / BEARISH / NEUTRAL
    confidence_score   DECIMAL(4,2),
    confidence_label   VARCHAR(10),              -- LOW / MEDIUM / HIGH
    open_zone          VARCHAR(10),              -- BREAKOUT / RANGE / BREAKDOWN

    -- Prior-day OHLC used for Camarilla, and the classifying open price.
    prior_ohlc         JSONB         NOT NULL,   -- {open,high,low,close}
    open_px            DECIMAL(10,2),

    -- Deterministic Camarilla levels {range,pivot,h3,h4,l3,l4}.
    camarilla          JSONB         NOT NULL,

    -- All four arms with entry/stop/target, R:R (gross + after cost),
    -- probability %, and per-arm gate results. Full grid kept for the
    -- "3 alternatives" the UI publishes alongside the primary.
    four_arms          JSONB         NOT NULL,

    -- The selected arm (spec §2.4). NULL when NO_TRADE.
    primary_arm        VARCHAR(20),

    -- Final GTT-ready levels for the primary arm (what Agent 5 will place).
    entry_price        DECIMAL(10,2),
    stop_price         DECIMAL(10,2),
    target_price       DECIMAL(10,2),
    rr_primary         DECIMAL(5,2),             -- gross reward/risk
    rr_after_cost      DECIMAL(5,2),             -- after cost model (§6.2) — the gate value

    -- Compression gate (§6.3): RCI = range(t-1)/SMA(range,20).
    compression_rci    DECIMAL(6,3),

    -- Position sizing + margin estimate {lots,lotSize,stopPoints,riskCapital,
    -- riskPerLot,marginEstimate,pointValue}. (§6.5, §6.8)
    sizing             JSONB,

    -- Full two-gate + guard-rail results {confidenceGate,rrGate,compressionGate,
    -- killSwitch,margin} for the trade card. (§5, §6)
    gate_results       JSONB,

    status             VARCHAR(20)   NOT NULL DEFAULT 'PRIMED',

    -- Populated on any terminal-without-trade outcome (compression, R:R fail, etc.).
    no_trade_reason    VARCHAR(200),

    -- Set when Agent 3 confirms entry and Agent 5 fills / GTT is placed.
    gtt_order_id       VARCHAR(64),
    fill_price         DECIMAL(10,2),
    realized_pnl       DECIMAL(12,2),
    close_reason       VARCHAR(100),

    approved_at        TIMESTAMPTZ,
    activated_at       TIMESTAMPTZ,              -- moved to CONFIRMED / submitted to Agent 5
    closed_at          TIMESTAMPTZ,

    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Agent 3 scans PRIMED/ARMED rows for the current day; Agent 4 filters by status.
CREATE INDEX idx_tfl_status_date       ON trade_future_ledger (status, trade_date DESC);
CREATE INDEX idx_tfl_user_date         ON trade_future_ledger (user_profile_id, trade_date DESC);
CREATE INDEX idx_tfl_agent1_signal     ON trade_future_ledger (agent1_signal_id);
