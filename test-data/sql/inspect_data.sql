-- ─────────────────────────────────────────────────────────────────────────────
-- inspect_data.sql — census of what data already exists in the system.
--
-- Goal: find REAL accumulated data points (not the seeded test fixtures) that can
--       become new backtest scenarios like A1-07 in the test matrix.
--
-- Seed fixtures are identifiable and excluded via these patterns:
--   agent1_signals : id::text LIKE 'a1000001-%'
--   trades         : id::text LIKE 'a2000001-%' OR 'a3000001-%'
--   user_profiles  : user_id LIKE 'TEST_%'
--
-- Run against whichever DB holds production data (dev NeonDB or the Azure managed
-- Postgres). psql example:
--   PGPASSWORD=*** psql "host=<host> dbname=<db> user=<user> sslmode=require" \
--     -f test-data/sql/inspect_data.sql
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

-- ── 0. Table census — rows, and how many are real vs seeded ──────────────────
SELECT 'agent1_signals' AS tbl,
       COUNT(*)                                              AS total_rows,
       COUNT(*) FILTER (WHERE id::text LIKE 'a1000001-%')    AS seeded,
       COUNT(*) FILTER (WHERE id::text NOT LIKE 'a1000001-%') AS real_rows
FROM agent1_signals
UNION ALL
SELECT 'trades', COUNT(*),
       COUNT(*) FILTER (WHERE id::text LIKE 'a2000001-%' OR id::text LIKE 'a3000001-%'),
       COUNT(*) FILTER (WHERE id::text NOT LIKE 'a2000001-%' AND id::text NOT LIKE 'a3000001-%')
FROM trades
UNION ALL
SELECT 'monitoring_evaluations', COUNT(*), NULL, COUNT(*) FROM monitoring_evaluations
UNION ALL
SELECT 'trade_ledger',   COUNT(*), NULL, COUNT(*) FROM trade_ledger
UNION ALL
SELECT 'trade_executions',COUNT(*), NULL, COUNT(*) FROM trade_executions
UNION ALL
SELECT 'trade_pnl',       COUNT(*), NULL, COUNT(*) FROM trade_pnl
UNION ALL
SELECT 'fii_dii_snapshots',COUNT(*), NULL, COUNT(*) FROM fii_dii_snapshots
UNION ALL
SELECT 'notifications',   COUNT(*), NULL, COUNT(*) FROM notifications
ORDER BY tbl;

-- ── 1. Agent 1 signal history at a glance (real rows only) ───────────────────
SELECT COUNT(*)              AS real_signals,
       MIN(timestamp)        AS earliest,
       MAX(timestamp)        AS latest,
       COUNT(DISTINCT expiry_date) AS distinct_expiries,
       COUNT(DISTINCT timestamp::date) AS distinct_days
FROM agent1_signals
WHERE id::text NOT LIKE 'a1000001-%';

-- ── 2. THE ONE YOU WANT: real signals as candidate fixtures ──────────────────
--     Flattens the common raw_inputs keys so each row reads like an A1-07 fixture.
SELECT timestamp::date                      AS run_date,
       expiry_date,
       spot,
       bias, strength, composite_score,
       confidence, confidence_label,
       vix_level, vix_regime, vix_direction,
       (raw_inputs->>'ema20')::numeric      AS ema20,
       (raw_inputs->>'ema50')::numeric      AS ema50,
       (raw_inputs->>'ema200')::numeric     AS ema200,
       (raw_inputs->>'pcr')::numeric        AS pcr,
       (raw_inputs->>'fii_net')::numeric    AS fii_net,
       (raw_inputs->>'dii_net')::numeric    AS dii_net,
       (raw_inputs->>'gift_nifty_premium')::numeric AS gift_prem,
       (raw_inputs->>'marketaux_sentiment')::numeric AS sentiment,
       commentary_divergence,
       data_gaps
FROM agent1_signals
WHERE id::text NOT LIKE 'a1000001-%'
ORDER BY timestamp DESC
LIMIT 50;

-- ── 3. Input-space coverage — where do the real signals cluster? ─────────────
--     Shows which bias / strength / VIX combinations you actually have data for,
--     so you can see which test-matrix rows have real backing vs none.
SELECT bias, strength, vix_regime, confidence_label,
       COUNT(*) AS n,
       ROUND(AVG(composite_score), 4) AS avg_score,
       ROUND(MIN(vix_level), 2) AS min_vix,
       ROUND(MAX(vix_level), 2) AS max_vix
FROM agent1_signals
WHERE id::text NOT LIKE 'a1000001-%'
GROUP BY bias, strength, vix_regime, confidence_label
ORDER BY n DESC;

-- ── 4. Signal → actual outcome (real backtest validation points) ─────────────
--     Every CLOSED trade with the signal that produced it and the realised P&L.
--     This is the gold: "signal said X → trade did Y".
SELECT t.trade_code,
       s.timestamp::date       AS signal_date,
       s.bias, s.strength, s.composite_score, s.confidence_label,
       t.strategy, t.spread_direction,
       t.status, t.close_reason,
       t.actual_pnl,
       t.confirmed_at::date     AS entered,
       t.closed_at::date        AS closed
FROM trades t
JOIN agent1_signals s ON s.id = t.agent1_signal_id
WHERE t.status = 'CLOSED'
  AND t.id::text NOT LIKE 'a2000001-%'
  AND t.id::text NOT LIKE 'a3000001-%'
ORDER BY t.closed_at DESC NULLS LAST
LIMIT 50;

-- ── 5. FII/DII data captured (Tier-2 inputs history) ─────────────────────────
SELECT segment,
       COUNT(*) AS days,
       MIN(trading_date) AS first_day,
       MAX(trading_date) AS last_day
FROM fii_dii_snapshots
GROUP BY segment
ORDER BY segment;

-- ── 6. Monitoring evaluation volume (how much live decision data exists) ──────
SELECT action,
       COUNT(*) AS n,
       MIN(evaluated_at)::date AS first_seen,
       MAX(evaluated_at)::date AS last_seen
FROM monitoring_evaluations
GROUP BY action
ORDER BY n DESC;

-- ── 7. Extract ONE signal in full fixture shape (paste into test matrix) ──────
--     Replace the date; returns the exact raw_inputs + computed outputs.
-- SELECT id, timestamp, expiry_date, spot, bias, strength, composite_score,
--        confidence, confidence_label, vix_level, vix_regime,
--        score_breakdown, data_gaps, raw_inputs
-- FROM agent1_signals
-- WHERE timestamp::date = '2026-07-15'
-- ORDER BY timestamp DESC;
