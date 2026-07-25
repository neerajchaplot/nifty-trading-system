-- ============================================================
-- V109 — Fix two bugs introduced by the V2 seed row
--
-- Bug 1: min_pop was seeded as 80.00 (should be 0.80).
--        The column is DECIMAL(4,2) so 80.00 stores as-is.
--        The UI multiplies by 100 for display, producing 8000%.
--        Fix: divide by 100 for any row where min_pop > 1.
--
-- Bug 2: spread_width_max was seeded as 100 (should be 150).
--        buildDefault() already uses 150 for new users; the
--        V2 seed row diverged. Fix the affected row.
-- ============================================================

UPDATE user_profiles
   SET min_pop = min_pop / 100
 WHERE min_pop > 1;

UPDATE user_profiles
   SET spread_width_max = 150
 WHERE user_id = 'default' AND spread_width_max = 100;
