-- Multi-user Phase 1: merge identity/auth onto user_profiles (no separate users table — every
-- table already scopes by user_profiles.id). Columns are nullable/defaulted here and backfilled
-- in V122; no Java/entity changes in this phase, so running agents are unaffected.
--
--   auth_provider    UPSTOX (live users) | GOOGLE (simulation-only users)
--   user_id          existing column, reused as the provider's user id (not renamed)
--   account_mode     SIMULATION | LIVE — gates real vs simulated order paths
ALTER TABLE user_profiles ADD COLUMN auth_provider VARCHAR(10);
ALTER TABLE user_profiles ADD COLUMN email         VARCHAR(255);
ALTER TABLE user_profiles ADD COLUMN display_name  VARCHAR(120);
ALTER TABLE user_profiles ADD COLUMN account_mode  VARCHAR(12);
ALTER TABLE user_profiles ADD COLUMN status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE user_profiles
    ADD CONSTRAINT chk_user_profiles_auth_provider
    CHECK (auth_provider IS NULL OR auth_provider IN ('UPSTOX', 'GOOGLE'));
ALTER TABLE user_profiles
    ADD CONSTRAINT chk_user_profiles_account_mode
    CHECK (account_mode IS NULL OR account_mode IN ('SIMULATION', 'LIVE'));

-- Uniqueness is now per provider: the same raw id from two providers must not collide.
ALTER TABLE user_profiles DROP CONSTRAINT IF EXISTS user_profiles_user_id_key;
ALTER TABLE user_profiles
    ADD CONSTRAINT uq_user_profiles_provider_user UNIQUE (auth_provider, user_id);
