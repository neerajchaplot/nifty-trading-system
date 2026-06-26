-- ─────────────────────────────────────────────────────────────────────────────
-- Cleanup: remove all test seed data
-- Run AFTER weekend testing is complete.
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

-- Delete in FK-safe order
DELETE FROM monitoring_evaluations
 WHERE trade_id IN (
     SELECT id FROM trades
      WHERE id::text LIKE 'a2000001-%' OR id::text LIKE 'a3000001-%'
 );

DELETE FROM trade_ledger
 WHERE trade_id IN (
     SELECT id FROM trades
      WHERE id::text LIKE 'a2000001-%' OR id::text LIKE 'a3000001-%'
 );

DELETE FROM trades
 WHERE id::text LIKE 'a2000001-%' OR id::text LIKE 'a3000001-%';

DELETE FROM agent1_signals
 WHERE id::text LIKE 'a1000001-%';

DELETE FROM user_profiles
 WHERE user_id LIKE 'TEST_%';

SELECT 'Cleanup complete' AS result;
