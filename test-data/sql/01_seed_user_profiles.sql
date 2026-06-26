-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: user_profiles — 4 capital tiers for testing position sizing
-- Fixed UUIDs so curl scripts can reference them by name in comments
-- Run: psql $DB_URL -f 01_seed_user_profiles.sql
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

-- Remove any stale test profiles first (idempotent)
DELETE FROM user_profiles WHERE user_id LIKE 'TEST_%';

INSERT INTO user_profiles (id, user_id, capital, min_pop, max_loss_pct, max_pop_popp_gap)
VALUES
    -- UP-01: 5 Lakh — smallest capital, fewest lots
    ('00000001-0000-0000-0000-000000000001',
     'TEST_USER_5L',       500000.00, 0.80, 1.50, 15.0),

    -- UP-02: 10 Lakh — medium capital
    ('00000001-0000-0000-0000-000000000002',
     'TEST_USER_10L',     1000000.00, 0.80, 1.50, 15.0),

    -- UP-03: 50 Lakh — larger capital, tests wider spreads
    ('00000001-0000-0000-0000-000000000003',
     'TEST_USER_50L',     5000000.00, 0.80, 1.50, 15.0),

    -- UP-04: 1 Crore — max capital tier
    ('00000001-0000-0000-0000-000000000004',
     'TEST_USER_1CR',    10000000.00, 0.80, 1.50, 15.0);

-- Verify
SELECT user_id, capital FROM user_profiles WHERE user_id LIKE 'TEST_%' ORDER BY capital;
