-- ============================================================
-- V121 — Signal channel (TRADING vs FUTURES) on agent1_signals.
--
-- The Trading tab and the Futures tab both generate Agent 1 signals under the same user, so a
-- single "latest" pointer let one override the other. Tagging each signal with its source keeps
-- them separate: TRADING is read per-user, FUTURES is read globally (admin-driven, shared).
--
-- Existing rows default to TRADING (they were all trading-tab signals).
-- ============================================================

ALTER TABLE agent1_signals ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'TRADING';

-- Fast "latest FUTURES signal for expiry" and "latest TRADING signal for user+expiry" lookups.
CREATE INDEX idx_agent1_signals_source
    ON agent1_signals (source, expiry_date, timestamp DESC);
