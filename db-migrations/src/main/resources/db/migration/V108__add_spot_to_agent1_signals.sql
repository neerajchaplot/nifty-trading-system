-- ============================================================
-- V108 — Add spot (Nifty 50 level at scoring time) to agent1_signals
--
-- The Nifty spot captured during a scoring run was previously only
-- persisted inside the raw_inputs JSONB. Promoting it to a first-class,
-- queryable column (mirrors vix_level) so the UI/mobile top strip and
-- audit queries can read it directly.
--
-- Backfill existing rows from raw_inputs->>'spot' so the currently
-- displayed signal shows a value immediately after deploy.
-- ============================================================

ALTER TABLE agent1_signals
    ADD COLUMN IF NOT EXISTS spot DECIMAL(10,2);

UPDATE agent1_signals
    SET spot = (raw_inputs->>'spot')::numeric
    WHERE spot IS NULL
      AND raw_inputs ? 'spot'
      AND raw_inputs->>'spot' IS NOT NULL;
