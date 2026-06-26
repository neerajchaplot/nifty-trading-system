-- ============================================================
-- V102 — Add data_gaps JSONB column to agent1_signals
--
-- Stores a JSON array of input names that were unavailable (null)
-- during a scoring run. Used for audit and data quality monitoring.
-- Example: ["VIX","GIFT_NIFTY","MARKETAUX"]
-- ============================================================

ALTER TABLE agent1_signals
    ADD COLUMN data_gaps JSONB;
