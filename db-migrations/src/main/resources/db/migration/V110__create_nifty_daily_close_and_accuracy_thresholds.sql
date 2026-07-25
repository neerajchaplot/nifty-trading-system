-- ============================================================
-- V110 — Nifty daily close store + Agent 1 accuracy thresholds
--
-- Two pieces supporting the price-based Agent 1 signal-accuracy metric
-- (Agent 4 signal-quality report):
--
--   1. nifty_daily_close — one settled Nifty 50 close per trading day.
--      WRITTEN BY AGENT 1 as a byproduct of the 200-candle historical fetch
--      it already performs on every scoring run (zero new Upstox calls).
--      READ BY AGENT 4 to resolve each signal's expiry-day close.
--
--   2. AGENT1_ACCURACY_THRESHOLDS — the point thresholds that define whether
--      a signal's directional promise was met, kept in reference_data (DB, not
--      code/yaml) so they can be tuned without a redeploy.
-- ============================================================

CREATE TABLE IF NOT EXISTS zupptrade_dev.nifty_daily_close (
    trade_date  DATE PRIMARY KEY,
    close       DECIMAL(10,2) NOT NULL,
    source      VARCHAR(50)   NOT NULL DEFAULT 'UPSTOX_HISTORICAL',
    fetched_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE zupptrade_dev.nifty_daily_close IS
    'Settled Nifty 50 daily close per trading day. Written by Agent 1 (byproduct of its '
    'historical candle fetch); read by Agent 4 to grade signal accuracy at expiry.';

-- Accuracy thresholds (points). A signal is ACCURATE when the net move from its
-- scoring-time spot to its expiry-day close meets its bias+strength promise:
--   BULLISH/BEARISH EXTREME → |move| >= extremePoints in the signalled direction
--   BULLISH/BEARISH MILD    → |move| >= mildPoints  in the signalled direction
--   WEAK (any bias) / NEUTRAL → |move| <= neutralBandPoints (stayed in range)
INSERT INTO zupptrade_dev.reference_data (key, value, source, ttl_hours)
VALUES (
    'AGENT1_ACCURACY_THRESHOLDS',
    '{"extremePoints": 200, "mildPoints": 100, "neutralBandPoints": 100}'::jsonb,
    'MANUAL',
    8760
)
ON CONFLICT (key) DO NOTHING;
