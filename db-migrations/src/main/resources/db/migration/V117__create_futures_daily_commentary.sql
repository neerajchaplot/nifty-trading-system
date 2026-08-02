-- ============================================================
-- V117 — Admin-submitted daily commentary for the FUTURES module.
--
-- Unlike the options flow (commentary passed in the Agent 1 /score request body from the UI),
-- futures commentary is MANDATORY and submitted by an ADMIN out-of-band (script/back office),
-- then read by the backend when a futures plan is generated. One row per trading day.
-- ============================================================

CREATE TABLE futures_daily_commentary (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_date   DATE          NOT NULL UNIQUE,   -- one commentary per day (upsert to replace)
    commentary   TEXT          NOT NULL,
    submitted_by VARCHAR(50)   NOT NULL,          -- admin user id, for audit
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
