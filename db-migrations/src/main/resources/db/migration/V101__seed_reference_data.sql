-- V101: Seed static reference data required by the trading system at startup.
--
-- NIFTY_LOT_SIZE: the current NSE lot size per circular. The system fetches the
-- live value from Upstox at startup and UPDATEs this row; this INSERT provides a
-- safe non-null fallback when the API is unavailable on first boot.
--
-- ON CONFLICT DO NOTHING makes this migration safe to re-run and idempotent across
-- all environments (dev, staging, prod).
--
-- Convention: V100 = api_tokens table. V101+ = seed data and incremental changes.

INSERT INTO reference_data (key, value, source, ttl_hours)
VALUES (
    'NIFTY_LOT_SIZE',
    '65'::jsonb,
    'flyway-seed',
    8760          -- 1 year TTL; refreshed by live Upstox API fetch, not by age
)
ON CONFLICT (key) DO NOTHING;
