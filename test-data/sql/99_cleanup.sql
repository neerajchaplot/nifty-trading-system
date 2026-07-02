-- ─────────────────────────────────────────────────────────────────────────────
-- Cleanup: remove all test seed data
-- Run AFTER weekend testing is complete.
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

-- Step 1: collect ALL trade IDs that reference test signals (seeded + dynamically created)
-- Seeded trades have fixed UUIDs (a2000001-*, a3000001-*).
-- Dynamically created trades (from recommend/confirm calls during testing) have
-- auto-generated UUIDs — they also reference a1000001-* signals, so must be
-- cleaned up first or the agent1_signals delete hits a FK violation.

-- Delete monitoring_evaluations for ALL trades referencing test signals
DELETE FROM monitoring_evaluations
 WHERE trade_id IN (
     SELECT id FROM trades
      WHERE agent1_signal_id::text LIKE 'a1000001-%'
         OR id::text LIKE 'a2000001-%'
         OR id::text LIKE 'a3000001-%'
 );

-- Delete trade_ledger for ALL trades referencing test signals
DELETE FROM trade_ledger
 WHERE trade_id IN (
     SELECT id FROM trades
      WHERE agent1_signal_id::text LIKE 'a1000001-%'
         OR id::text LIKE 'a2000001-%'
         OR id::text LIKE 'a3000001-%'
 );

-- Delete ALL trades referencing test signals (seeded + dynamically created)
DELETE FROM trades
 WHERE agent1_signal_id::text LIKE 'a1000001-%'
    OR id::text LIKE 'a2000001-%'
    OR id::text LIKE 'a3000001-%';

-- Now safe to delete signals (no FK dependents remain)
DELETE FROM agent1_signals
 WHERE id::text LIKE 'a1000001-%';

DELETE FROM user_profiles
 WHERE user_id LIKE 'TEST_%';

SELECT 'Cleanup complete' AS result;
