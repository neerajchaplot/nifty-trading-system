-- ============================================================
-- V118 — Fix run_phase column type on trade_future_ledger.
--
-- V116 created run_phase as SMALLINT, but FutureTradeLedgerEntity maps it to a Java int,
-- so Hibernate schema-validation (ddl-auto=validate) expects INTEGER and fails on boot.
-- Widen to INTEGER to match the entity (run_phase holds 900 / 931 — int is the right fit).
-- ============================================================

ALTER TABLE trade_future_ledger ALTER COLUMN run_phase TYPE INTEGER;
