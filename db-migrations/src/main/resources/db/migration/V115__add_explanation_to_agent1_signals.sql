-- Plain-English "why" for each scored signal, surfaced behind the market-strip help icon in the UI.
-- Deterministic text built at scoring time by agent1 SignalExplanationService. Nullable — older
-- rows (and any run where generation failed) simply have no explanation.
ALTER TABLE agent1_signals ADD COLUMN explanation TEXT;
