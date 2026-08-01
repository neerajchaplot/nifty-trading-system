-- ─────────────────────────────────────────────────────────────────────────────
-- V113 — sim_clock: the virtual clock backing store for the simulation harness.
--
-- Singleton row (id = 1) holding the current VIRTUAL instant. Advanced by the
-- conductor via Agent 3's /sim/clock endpoints; read by SimClock (java.time.Clock)
-- ONLY under the 'simulation' Spring profile.
--
-- Inert in production: the table exists but nothing reads it unless the simulation
-- profile is active (which must never happen in prod). Lives in the shared schema so
-- that, later, every agent's SimClock can read the same virtual time cross-process.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sim_clock (
    id          SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),  -- singleton guard
    current_ts  TIMESTAMPTZ  NOT NULL,                              -- current virtual instant
    scenario_id UUID,                                              -- reserved: which scenario is loaded
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Bootstrap the single row so reads never fail before the first /sim/clock/set.
INSERT INTO sim_clock (id, current_ts) VALUES (1, NOW())
ON CONFLICT (id) DO NOTHING;
