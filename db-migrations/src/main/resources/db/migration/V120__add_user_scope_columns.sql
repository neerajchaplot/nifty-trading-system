-- Multi-user Phase 1: scope agent outputs to a user. Adds a nullable user_profile_id (FK to
-- user_profiles) to the tables that currently have no user linkage. Nullable + unmapped by
-- entities, so inserts keep working; backfilled in V122, tightened to NOT NULL in Phase 9.
-- (trades already carries user_profile_id.)
-- Note: scoring_audit_log is in the CLAUDE.md spec but was never actually created by any
-- migration, so it is intentionally NOT scoped here.
ALTER TABLE agent1_signals  ADD COLUMN user_profile_id UUID REFERENCES user_profiles(id);
ALTER TABLE notifications   ADD COLUMN user_profile_id UUID REFERENCES user_profiles(id);
ALTER TABLE critical_alerts ADD COLUMN user_profile_id UUID REFERENCES user_profiles(id);

CREATE INDEX idx_agent1_signals_user  ON agent1_signals  (user_profile_id);
CREATE INDEX idx_notifications_user   ON notifications   (user_profile_id);
CREATE INDEX idx_critical_alerts_user ON critical_alerts (user_profile_id);
