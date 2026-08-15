-- Multi-user Phase 1: broker tokens become per-user. Adds a nullable user_profile_id to
-- api_tokens. The existing UNIQUE(service) is intentionally KEPT here — upstox-auth's current
-- writer still upserts on (service). The constraint swap to UNIQUE(user_profile_id, service)
-- happens in Phase 4 together with the per-user writer change, so nothing breaks now.
ALTER TABLE api_tokens ADD COLUMN user_profile_id UUID REFERENCES user_profiles(id);
CREATE INDEX idx_api_tokens_user ON api_tokens (user_profile_id);
