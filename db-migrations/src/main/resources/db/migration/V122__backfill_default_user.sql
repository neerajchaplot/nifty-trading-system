-- Multi-user Phase 1: backfill the pre-existing single-user data onto the one profile.
-- Safe because the system is single-user at this migration point: exactly one user_profiles row
-- exists (seeded in V2, later claimed by the live Upstox identity). All prior agent output
-- therefore belongs to that one profile.

-- 1. Stamp identity/mode on the existing profile(s).
UPDATE user_profiles
   SET auth_provider = 'UPSTOX',
       account_mode  = 'LIVE',
       status        = 'ACTIVE'
 WHERE auth_provider IS NULL;

-- 2. Attribute all existing user-scoped rows to that single profile.
--    (ORDER BY … LIMIT 1 is deterministic given the single-user precondition above.)
UPDATE agent1_signals
   SET user_profile_id = (SELECT id FROM user_profiles ORDER BY updated_at ASC LIMIT 1)
 WHERE user_profile_id IS NULL;

UPDATE notifications
   SET user_profile_id = (SELECT id FROM user_profiles ORDER BY updated_at ASC LIMIT 1)
 WHERE user_profile_id IS NULL;

UPDATE critical_alerts
   SET user_profile_id = (SELECT id FROM user_profiles ORDER BY updated_at ASC LIMIT 1)
 WHERE user_profile_id IS NULL;

UPDATE api_tokens
   SET user_profile_id = (SELECT id FROM user_profiles ORDER BY updated_at ASC LIMIT 1)
 WHERE user_profile_id IS NULL;
