-- Add Agent 1 tier weight overrides and min_roc_pct to user_profiles.
-- Weights default to system defaults from application.yml.
-- DB constraint enforces sum = 1.0000 to prevent silent misconfiguration.

ALTER TABLE user_profiles
    ADD COLUMN IF NOT EXISTS min_roc_pct    DECIMAL(5,2) NOT NULL DEFAULT 0.50,
    ADD COLUMN IF NOT EXISTS tier1a_weight  DECIMAL(5,4) NOT NULL DEFAULT 0.3000,
    ADD COLUMN IF NOT EXISTS tier1b_weight  DECIMAL(5,4) NOT NULL DEFAULT 0.2000,
    ADD COLUMN IF NOT EXISTS tier2_weight   DECIMAL(5,4) NOT NULL DEFAULT 0.3000,
    ADD COLUMN IF NOT EXISTS tier3_weight   DECIMAL(5,4) NOT NULL DEFAULT 0.1000,
    ADD COLUMN IF NOT EXISTS tier4_weight   DECIMAL(5,4) NOT NULL DEFAULT 0.1000;

ALTER TABLE user_profiles
    ADD CONSTRAINT chk_weights_sum
        CHECK (ROUND(tier1a_weight + tier1b_weight + tier2_weight + tier3_weight + tier4_weight, 4) = 1.0000);
