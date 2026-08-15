-- Multi-user Phase 2: designate the admin/default user. The admin profile's Upstox token powers
-- the shared live market-data reads that simulation (Google) users borrow. Exactly one profile is
-- flagged; the existing single live profile (backfilled in V122) becomes admin.
ALTER TABLE user_profiles ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT false;

-- Flag the existing profile (single-user precondition still holds at this point).
UPDATE user_profiles
   SET is_admin = true
 WHERE id = (SELECT id FROM user_profiles ORDER BY created_at ASC LIMIT 1);
