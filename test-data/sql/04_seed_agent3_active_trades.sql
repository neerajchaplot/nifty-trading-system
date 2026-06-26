-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: trades — ACTIVE rows with full monitor_config for Agent 3 testing
--
-- These are pre-set at specific threshold states so you can pass synthetic
-- niftySpot values and observe the correct HOLD / WATCH / READJUST / EXIT action.
--
-- All trades use expiry 2026-07-01. monitor_config includes shortLeg/longLeg
-- instrument keys required by Agent 3's LiveMarketDataService (used only when
-- NOT in override mode — override skips the live fetch entirely).
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

DELETE FROM trades WHERE id::text LIKE 'a3000001-%';

-- ── T-301: BullPutSpread — should produce HOLD ───────────────────────────────
-- Setup:  SELL 23500 PE, BUY 23400 PE. Spot at entry: 24350.
-- Test:   Send override niftySpot=24200 → well above T1 (23650) → HOLD
-- T1=23650 (short_strike + 150), T2=23575 (short_strike + 75), T3=23500 (breach)
INSERT INTO trades (
    id, status, strategy, expiry_date,
    legs, summary, market_context, thresholds, monitor_config, entry_fills,
    confirmed_at
) VALUES (
    'a3000001-0000-0000-0000-000000000001',
    'ACTIVE', 'BullPutSpread', '2026-07-01',
    '[
        {"action":"SELL","strike":23500,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500"},
        {"action":"BUY", "strike":23400,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400"}
    ]'::jsonb,
    '{"netPremiumPerUnit":28.40,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":18460,"theoreticalMaxLossTotal":63050,"realExpectedLossTotal":31525}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '{"t1WatchNifty":23650,"t2ReadjustNifty":23575,"t2ReadjustPnlLoss":15763,"t3ExitNifty":23500,"t3ExitPnlLoss":31525}'::jsonb,
    '{
        "strategy": "BullPutSpread",
        "spreadType": "CREDIT",
        "expiryDate": "2026-07-01",
        "shortLeg":  {"instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","strike":23500,"optionType":"PE","entryLtp":48.20},
        "longLeg":   {"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","strike":23400,"optionType":"PE","entryLtp":19.80},
        "entryNetPremium": 28.40,
        "lots": 10,
        "lotSize": 65,
        "t1WatchNifty": 23650,
        "t2ReadjustNifty": 23575,
        "t2ReadjustPnlLoss": 15763,
        "t3ExitNifty": 23500,
        "t3ExitPnlLoss": 31525,
        "maxLossTheoretical": 63050
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000001-L0","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","action":"SELL","strike":23500,"optionType":"PE","quantityFilled":650,"averageFillPrice":48.20},
        {"orderId":"SIM-A3000001-L1","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","action":"BUY","strike":23400,"optionType":"PE","quantityFilled":650,"averageFillPrice":19.80}
    ]'::jsonb,
    NOW() - INTERVAL '2 hours'
);

-- ── T-302: BullPutSpread — should produce WATCH ──────────────────────────────
-- Test: Send override niftySpot=23650 → exactly at T1 → WATCH
INSERT INTO trades (
    id, status, strategy, expiry_date,
    legs, summary, market_context, thresholds, monitor_config, entry_fills,
    confirmed_at
) VALUES (
    'a3000001-0000-0000-0000-000000000002',
    'ACTIVE', 'BullPutSpread', '2026-07-01',
    '[
        {"action":"SELL","strike":23500,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500"},
        {"action":"BUY", "strike":23400,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400"}
    ]'::jsonb,
    '{"netPremiumPerUnit":28.40,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":18460,"theoreticalMaxLossTotal":63050,"realExpectedLossTotal":31525}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '{"t1WatchNifty":23650,"t2ReadjustNifty":23575,"t2ReadjustPnlLoss":15763,"t3ExitNifty":23500,"t3ExitPnlLoss":31525}'::jsonb,
    '{
        "strategy": "BullPutSpread",
        "spreadType": "CREDIT",
        "expiryDate": "2026-07-01",
        "shortLeg":  {"instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","strike":23500,"optionType":"PE","entryLtp":48.20},
        "longLeg":   {"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","strike":23400,"optionType":"PE","entryLtp":19.80},
        "entryNetPremium": 28.40,
        "lots": 10,
        "lotSize": 65,
        "t1WatchNifty": 23650,
        "t2ReadjustNifty": 23575,
        "t2ReadjustPnlLoss": 15763,
        "t3ExitNifty": 23500,
        "t3ExitPnlLoss": 31525,
        "maxLossTheoretical": 63050
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000002-L0","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","action":"SELL","strike":23500,"optionType":"PE","quantityFilled":650,"averageFillPrice":48.20},
        {"orderId":"SIM-A3000002-L1","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","action":"BUY","strike":23400,"optionType":"PE","quantityFilled":650,"averageFillPrice":19.80}
    ]'::jsonb,
    NOW() - INTERVAL '2 hours'
);

-- ── T-303: BullPutSpread — should produce READJUST ───────────────────────────
-- Test: Send override niftySpot=23575 → at T2 → READJUST
INSERT INTO trades (
    id, status, strategy, expiry_date,
    legs, summary, market_context, thresholds, monitor_config, entry_fills,
    confirmed_at
) VALUES (
    'a3000001-0000-0000-0000-000000000003',
    'ACTIVE', 'BullPutSpread', '2026-07-01',
    '[
        {"action":"SELL","strike":23500,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500"},
        {"action":"BUY", "strike":23400,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400"}
    ]'::jsonb,
    '{"netPremiumPerUnit":28.40,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":18460,"theoreticalMaxLossTotal":63050,"realExpectedLossTotal":31525}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '{"t1WatchNifty":23650,"t2ReadjustNifty":23575,"t2ReadjustPnlLoss":15763,"t3ExitNifty":23500,"t3ExitPnlLoss":31525}'::jsonb,
    '{
        "strategy": "BullPutSpread",
        "spreadType": "CREDIT",
        "expiryDate": "2026-07-01",
        "shortLeg":  {"instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","strike":23500,"optionType":"PE","entryLtp":48.20},
        "longLeg":   {"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","strike":23400,"optionType":"PE","entryLtp":19.80},
        "entryNetPremium": 28.40,
        "lots": 10,
        "lotSize": 65,
        "t1WatchNifty": 23650,
        "t2ReadjustNifty": 23575,
        "t2ReadjustPnlLoss": 15763,
        "t3ExitNifty": 23500,
        "t3ExitPnlLoss": 31525,
        "maxLossTheoretical": 63050
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000003-L0","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","action":"SELL","strike":23500,"optionType":"PE","quantityFilled":650,"averageFillPrice":48.20},
        {"orderId":"SIM-A3000003-L1","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","action":"BUY","strike":23400,"optionType":"PE","quantityFilled":650,"averageFillPrice":19.80}
    ]'::jsonb,
    NOW() - INTERVAL '2 hours'
);

-- ── T-304: BullPutSpread — should produce EXIT ───────────────────────────────
-- Test: Send override niftySpot=23450 → breaches short strike 23500 → EXIT
INSERT INTO trades (
    id, status, strategy, expiry_date,
    legs, summary, market_context, thresholds, monitor_config, entry_fills,
    confirmed_at
) VALUES (
    'a3000001-0000-0000-0000-000000000004',
    'ACTIVE', 'BullPutSpread', '2026-07-01',
    '[
        {"action":"SELL","strike":23500,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500"},
        {"action":"BUY", "strike":23400,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400"}
    ]'::jsonb,
    '{"netPremiumPerUnit":28.40,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":18460,"theoreticalMaxLossTotal":63050,"realExpectedLossTotal":31525}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '{"t1WatchNifty":23650,"t2ReadjustNifty":23575,"t2ReadjustPnlLoss":15763,"t3ExitNifty":23500,"t3ExitPnlLoss":31525}'::jsonb,
    '{
        "strategy": "BullPutSpread",
        "spreadType": "CREDIT",
        "expiryDate": "2026-07-01",
        "shortLeg":  {"instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","strike":23500,"optionType":"PE","entryLtp":48.20},
        "longLeg":   {"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","strike":23400,"optionType":"PE","entryLtp":19.80},
        "entryNetPremium": 28.40,
        "lots": 10,
        "lotSize": 65,
        "t1WatchNifty": 23650,
        "t2ReadjustNifty": 23575,
        "t2ReadjustPnlLoss": 15763,
        "t3ExitNifty": 23500,
        "t3ExitPnlLoss": 31525,
        "maxLossTheoretical": 63050
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000004-L0","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","action":"SELL","strike":23500,"optionType":"PE","quantityFilled":650,"averageFillPrice":48.20},
        {"orderId":"SIM-A3000004-L1","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","action":"BUY","strike":23400,"optionType":"PE","quantityFilled":650,"averageFillPrice":19.80}
    ]'::jsonb,
    NOW() - INTERVAL '2 hours'
);

-- ── T-305: BullPutSpread — VIX spike → override to EXIT regardless of price ──
-- Test: Send override niftySpot=24100 (above T1) BUT vix=32.0 (>30% intraday spike)
-- Expected: EXIT (VIX spike override takes precedence over price action)
-- Note: Agent 3 computes VIX change vs previous evaluation's VIX.
--       We seed a previous eval row so the spike is detectable.
INSERT INTO trades (
    id, status, strategy, expiry_date,
    legs, summary, market_context, thresholds, monitor_config, entry_fills,
    confirmed_at
) VALUES (
    'a3000001-0000-0000-0000-000000000005',
    'ACTIVE', 'BullPutSpread', '2026-07-01',
    '[
        {"action":"SELL","strike":23500,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500"},
        {"action":"BUY", "strike":23400,"optionType":"PE","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400"}
    ]'::jsonb,
    '{"netPremiumPerUnit":28.40,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":18460,"theoreticalMaxLossTotal":63050,"realExpectedLossTotal":31525}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '{"t1WatchNifty":23650,"t2ReadjustNifty":23575,"t2ReadjustPnlLoss":15763,"t3ExitNifty":23500,"t3ExitPnlLoss":31525}'::jsonb,
    '{
        "strategy": "BullPutSpread",
        "spreadType": "CREDIT",
        "expiryDate": "2026-07-01",
        "shortLeg":  {"instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","strike":23500,"optionType":"PE","entryLtp":48.20},
        "longLeg":   {"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","strike":23400,"optionType":"PE","entryLtp":19.80},
        "entryNetPremium": 28.40,
        "lots": 10,
        "lotSize": 65,
        "t1WatchNifty": 23650,
        "t2ReadjustNifty": 23575,
        "t2ReadjustPnlLoss": 15763,
        "t3ExitNifty": 23500,
        "t3ExitPnlLoss": 31525,
        "maxLossTheoretical": 63050
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000005-L0","instrumentKey":"NSE_FO|REPLACE_SELL_PE_23500","action":"SELL","strike":23500,"optionType":"PE","quantityFilled":650,"averageFillPrice":48.20},
        {"orderId":"SIM-A3000005-L1","instrumentKey":"NSE_FO|REPLACE_BUY_PE_23400","action":"BUY","strike":23400,"optionType":"PE","quantityFilled":650,"averageFillPrice":19.80}
    ]'::jsonb,
    NOW() - INTERVAL '2 hours'
);

-- ── T-306: BullCallSpread (debit) — should produce T1 PROFIT EXIT ────────────
-- Setup:  BUY 24200 CE, SELL 24400 CE. Entry net debit: 72.80/unit.
-- T1 profit target: 0.5% RoC → Nifty at 24470.
-- Test: Send override niftySpot=24480 (above T1) → EXIT (book profit)
INSERT INTO trades (
    id, status, strategy, expiry_date,
    legs, summary, market_context, thresholds, monitor_config, entry_fills,
    confirmed_at
) VALUES (
    'a3000001-0000-0000-0000-000000000006',
    'ACTIVE', 'BullCallSpread', '2026-07-01',
    '[
        {"action":"BUY", "strike":24200,"optionType":"CE","instrumentKey":"NSE_FO|REPLACE_BUY_CE_24200"},
        {"action":"SELL","strike":24400,"optionType":"CE","instrumentKey":"NSE_FO|REPLACE_SELL_CE_24400"}
    ]'::jsonb,
    '{"netPremiumPerUnit":72.80,"spreadWidth":200,"lots":2,"lotSize":65,"maxProfitTotal":16380,"theoreticalMaxLossTotal":9464,"realExpectedLossTotal":4732}'::jsonb,
    '{"spot":24350,"vix":15.20,"ivRegime":"FAIR","bias":"BULLISH","strength":"EXTREME","dte":7}'::jsonb,
    '{"t1ProfitNifty":24470,"t2StretchNifty":24575,"t3LossPnl":4732}'::jsonb,
    '{
        "strategy": "BullCallSpread",
        "spreadType": "DEBIT",
        "expiryDate": "2026-07-01",
        "shortLeg":  {"instrumentKey":"NSE_FO|REPLACE_SELL_CE_24400","strike":24400,"optionType":"CE","entryLtp":85.60},
        "longLeg":   {"instrumentKey":"NSE_FO|REPLACE_BUY_CE_24200","strike":24200,"optionType":"CE","entryLtp":158.40},
        "entryNetPremium": 72.80,
        "lots": 2,
        "lotSize": 65,
        "t1ProfitNifty": 24470,
        "t2StretchNifty": 24575,
        "t3LossPnl": 4732
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000006-L0","instrumentKey":"NSE_FO|REPLACE_BUY_CE_24200","action":"BUY","strike":24200,"optionType":"CE","quantityFilled":130,"averageFillPrice":158.40},
        {"orderId":"SIM-A3000006-L1","instrumentKey":"NSE_FO|REPLACE_SELL_CE_24400","action":"SELL","strike":24400,"optionType":"CE","quantityFilled":130,"averageFillPrice":85.60}
    ]'::jsonb,
    NOW() - INTERVAL '2 hours'
);

-- ── T-307: BullCallSpread (debit) — T3 loss cut ──────────────────────────────
-- Test: Send override shortLegLtp=12.50 / longLegLtp=130.00
--       Simulated net = 12.50 - 130.00 = -117.50 vs entry -72.80
--       MTM loss = 65×(117.50-72.80)×2 = 5811 > 50% of premium paid (4732) → EXIT
INSERT INTO trades (
    id, status, strategy, expiry_date,
    legs, summary, market_context, thresholds, monitor_config, entry_fills,
    confirmed_at
) VALUES (
    'a3000001-0000-0000-0000-000000000007',
    'ACTIVE', 'BullCallSpread', '2026-07-01',
    '[
        {"action":"BUY", "strike":24200,"optionType":"CE","instrumentKey":"NSE_FO|REPLACE_BUY_CE_24200"},
        {"action":"SELL","strike":24400,"optionType":"CE","instrumentKey":"NSE_FO|REPLACE_SELL_CE_24400"}
    ]'::jsonb,
    '{"netPremiumPerUnit":72.80,"spreadWidth":200,"lots":2,"lotSize":65,"maxProfitTotal":16380,"theoreticalMaxLossTotal":9464,"realExpectedLossTotal":4732}'::jsonb,
    '{"spot":24350,"vix":15.20,"ivRegime":"FAIR","bias":"BULLISH","strength":"EXTREME","dte":7}'::jsonb,
    '{"t1ProfitNifty":24470,"t2StretchNifty":24575,"t3LossPnl":4732}'::jsonb,
    '{
        "strategy": "BullCallSpread",
        "spreadType": "DEBIT",
        "expiryDate": "2026-07-01",
        "shortLeg":  {"instrumentKey":"NSE_FO|REPLACE_SELL_CE_24400","strike":24400,"optionType":"CE","entryLtp":85.60},
        "longLeg":   {"instrumentKey":"NSE_FO|REPLACE_BUY_CE_24200","strike":24200,"optionType":"CE","entryLtp":158.40},
        "entryNetPremium": 72.80,
        "lots": 2,
        "lotSize": 65,
        "t1ProfitNifty": 24470,
        "t2StretchNifty": 24575,
        "t3LossPnl": 4732
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000007-L0","instrumentKey":"NSE_FO|REPLACE_BUY_CE_24200","action":"BUY","strike":24200,"optionType":"CE","quantityFilled":130,"averageFillPrice":158.40},
        {"orderId":"SIM-A3000007-L1","instrumentKey":"NSE_FO|REPLACE_SELL_CE_24400","action":"SELL","strike":24400,"optionType":"CE","quantityFilled":130,"averageFillPrice":85.60}
    ]'::jsonb,
    NOW() - INTERVAL '2 hours'
);

SELECT id, strategy, status FROM trades WHERE id::text LIKE 'a3000001-%' ORDER BY id;
