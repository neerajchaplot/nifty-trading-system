-- Multi-user Phase 4: each user keeps their own Upstox token instead of overwriting a single row.
-- Drop the system-wide UNIQUE(service) and make uniqueness per user. Existing rows already carry
-- user_profile_id (V122), so the composite unique is satisfiable.
--
-- After this: multiple service='UPSTOX' rows exist (the admin/system token + one per live user).
-- Writers/readers are updated in the same change to scope by user_profile_id (admin row = the
-- shared market-data token).
ALTER TABLE api_tokens DROP CONSTRAINT IF EXISTS api_tokens_service_key;
ALTER TABLE api_tokens ADD CONSTRAINT uq_api_tokens_user_service UNIQUE (user_profile_id, service);
