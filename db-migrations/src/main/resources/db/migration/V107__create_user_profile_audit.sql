-- Audit trail for user profile changes.
-- One row per save operation — old and new values stored as JSONB snapshots.

CREATE TABLE user_profile_audit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_profile_id UUID NOT NULL REFERENCES user_profiles(id),
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    old_values      JSONB NOT NULL,
    new_values      JSONB NOT NULL
);

CREATE INDEX idx_user_profile_audit_profile_id ON user_profile_audit(user_profile_id);
CREATE INDEX idx_user_profile_audit_changed_at ON user_profile_audit(changed_at DESC);
