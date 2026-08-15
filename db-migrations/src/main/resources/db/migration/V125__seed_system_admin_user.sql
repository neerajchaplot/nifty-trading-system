-- Multi-user Phase 4: a dedicated SYSTEM_ADMIN user that holds the shared Upstox market-data token
-- (written daily by upstox-auth, read by all agents). Distinct from any human trading profile.
-- Exactly one admin exists. Risk params/weights use their column defaults (weights sum to 1.0000).
--
-- NOTE: rename user_id here if you want the admin to map to a specific Upstox account id.

INSERT INTO user_profiles (user_id, auth_provider, account_mode, status, is_admin, capital)
VALUES ('SYSTEM_ADMIN', 'UPSTOX', 'LIVE', 'ACTIVE', true, 500000.00)
ON CONFLICT (auth_provider, user_id) DO UPDATE
    SET is_admin = true, account_mode = 'LIVE', status = 'ACTIVE';

-- Exactly one admin: demote any previously-flagged profile (e.g. the V123 default row).
UPDATE user_profiles
   SET is_admin = false
 WHERE is_admin = true
   AND NOT (user_id = 'SYSTEM_ADMIN' AND auth_provider = 'UPSTOX');

-- Re-point the existing single UPSTOX market-data token to the new admin so agents keep reading it.
UPDATE api_tokens
   SET user_profile_id = (SELECT id FROM user_profiles
                           WHERE user_id = 'SYSTEM_ADMIN' AND auth_provider = 'UPSTOX')
 WHERE service = 'UPSTOX';
