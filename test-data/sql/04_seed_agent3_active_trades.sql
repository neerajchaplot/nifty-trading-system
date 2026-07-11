-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: trades — ACTIVE rows with full monitor_config for Agent 3 testing
--
-- All tests use the niftySpot override body (offline mode — no Upstox needed).
-- Override body: { niftySpot, vix, shortLegLtp, longLegLtp, shortLegIv }
--
-- Expiry: 2026-07-07. Instrument keys: PLACEHOLDER — update from 2026-07-03 capture.
-- ATM=24050. Entry context: spot=24350 (pretend entry was intraday higher).
--
-- BullPutSpread (T-301 to T-305): SELL PE 24000 / BUY PE 23900
--   Short strike: 24000 → T1=24150, T2=24075, T3=24000
--   Thresholds: t2LossThreshold=11968 (50% of 23937), t3LossThreshold=23937
--
-- CreditSpreadMonitorStrategy uses PoP-based decisions (not Nifty level thresholds):
--   PoP < 65%  → EXIT       PoP 65–74% → READJUST
--   PoP 75–79% → WATCH      PoP ≥ 80%  → HOLD
--   VIX > 24   → PAUSE      Spot breach short strike → EXIT
--
-- IV overrides calibrated for DTE=4 (test date 2026-07-03, expiry 2026-07-07):
--   HOLD     : spot=24450, σ=0.185 → PoP≈83.8%
--   WATCH    : spot=24350, σ=0.192 → PoP≈77.2%
--   READJUST : spot=24200, σ=0.200 → PoP≈66.3%
--   EXIT     : spot=23950 → short strike 24000 breached (PoP irrelevant)
--
-- BullCallSpread (T-306, T-307): BUY CE 24100 / SELL CE 24250
--   entryNetDebit=58.90. t1WatchNiftyLevel=24200. t2ReadjustNiftyLevel=24250.
--   t2LossThreshold=3829 (50% of totalPremiumPaid: 58.90 × 65 × 2 = 7657 / 2)
--   Override: 24250 (T2 profit EXIT), 24000 + low LTPs (loss cut EXIT)
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

DELETE FROM trades WHERE id::text LIKE 'a3000001-%';

-- ─────────────────────────────────────────────────────────────────────────────
-- Shared BullPutSpread config (T-301 to T-305)
--   Short PE 24000: NSE_FO|44621  entryLtp=64.50
--   Long  PE 23900: NSE_FO|44617  entryLtp=38.15
--   actualNetPremiumPerUnit=26.35, lots=10, qty=650
--   t2LossThreshold=11968, t3LossThreshold=23937
-- ─────────────────────────────────────────────────────────────────────────────

-- ── T-301: BullPutSpread — should produce HOLD ───────────────────────────────
-- Override: niftySpot=24450, σ=0.185 → PoP≈83.8% ≥ 80% → HOLD
INSERT INTO trades (
    id, user_profile_id,
    status, strategy, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'a3000001-0000-0000-0000-000000000001',
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'ACTIVE', 'BULL_PUT_SPREAD', '2026-07-07', 7,
    '[
        {"action":"SELL","strike":24000,"optionType":"PE","instrumentKey":"NSE_FO|44621"},
        {"action":"BUY", "strike":23900,"optionType":"PE","instrumentKey":"NSE_FO|44617"}
    ]'::jsonb,
    '{"netPremiumPerUnit":26.35,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":17128,"theoreticalMaxLossTotal":47873,"realExpectedLossTotal":23937}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t3ExitNiftyLevel":24000,"t2LossThreshold":11968,"t3LossThreshold":23937}'::jsonb,
    '{
        "tradeId": "a3000001-0000-0000-0000-000000000001",
        "strategy": "BULL_PUT_SPREAD",
        "spreadDirection": "CREDIT",
        "shortLeg": {"strike":24000,"optionType":"PE","action":"SELL","ltp":64.50,"instrumentKey":"NSE_FO|44621"},
        "longLeg":  {"strike":23900,"optionType":"PE","action":"BUY", "ltp":38.15,"instrumentKey":"NSE_FO|44617"},
        "actualNetPremiumPerUnit": 26.35,
        "lots": 10,
        "lotSize": 65,
        "maxProfitTotal": 17128,
        "actualMaxLossTotal": 47873,
        "slippageAlert": false,
        "slippageAmount": 0,
        "thresholds": {
            "t1WatchNiftyLevel": 24150,
            "t2ReadjustNiftyLevel": 24075,
            "t3ExitNiftyLevel": 24000,
            "t2LossThreshold": 11968,
            "t3LossThreshold": 23937
        },
        "expiryDate": "2026-07-07",
        "dte": 7
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000001-L0","instrumentKey":"NSE_FO|44621","action":"SELL","strike":24000,"optionType":"PE","quantityFilled":650,"averageFillPrice":64.50},
        {"orderId":"SIM-A3000001-L1","instrumentKey":"NSE_FO|44617","action":"BUY","strike":23900,"optionType":"PE","quantityFilled":650,"averageFillPrice":38.15}
    ]'::jsonb,
    NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours 50 minutes', NOW() - INTERVAL '2 hours', 'T-20260703-0301'
);

-- ── T-302: BullPutSpread — should produce WATCH ──────────────────────────────
-- Override: niftySpot=24350, σ=0.192 → PoP≈77.2% (75–79%) → WATCH
INSERT INTO trades (
    id, user_profile_id,
    status, strategy, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'a3000001-0000-0000-0000-000000000002',
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'ACTIVE', 'BULL_PUT_SPREAD', '2026-07-07', 7,
    '[
        {"action":"SELL","strike":24000,"optionType":"PE","instrumentKey":"NSE_FO|44621"},
        {"action":"BUY", "strike":23900,"optionType":"PE","instrumentKey":"NSE_FO|44617"}
    ]'::jsonb,
    '{"netPremiumPerUnit":26.35,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":17128,"theoreticalMaxLossTotal":47873,"realExpectedLossTotal":23937}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t3ExitNiftyLevel":24000,"t2LossThreshold":11968,"t3LossThreshold":23937}'::jsonb,
    '{
        "tradeId": "a3000001-0000-0000-0000-000000000002",
        "strategy": "BULL_PUT_SPREAD",
        "spreadDirection": "CREDIT",
        "shortLeg": {"strike":24000,"optionType":"PE","action":"SELL","ltp":64.50,"instrumentKey":"NSE_FO|44621"},
        "longLeg":  {"strike":23900,"optionType":"PE","action":"BUY", "ltp":38.15,"instrumentKey":"NSE_FO|44617"},
        "actualNetPremiumPerUnit": 26.35,
        "lots": 10,
        "lotSize": 65,
        "maxProfitTotal": 17128,
        "actualMaxLossTotal": 47873,
        "slippageAlert": false,
        "slippageAmount": 0,
        "thresholds": {
            "t1WatchNiftyLevel": 24150,
            "t2ReadjustNiftyLevel": 24075,
            "t3ExitNiftyLevel": 24000,
            "t2LossThreshold": 11968,
            "t3LossThreshold": 23937
        },
        "expiryDate": "2026-07-07",
        "dte": 7
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000002-L0","instrumentKey":"NSE_FO|44621","action":"SELL","strike":24000,"optionType":"PE","quantityFilled":650,"averageFillPrice":64.50},
        {"orderId":"SIM-A3000002-L1","instrumentKey":"NSE_FO|44617","action":"BUY","strike":23900,"optionType":"PE","quantityFilled":650,"averageFillPrice":38.15}
    ]'::jsonb,
    NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours 50 minutes', NOW() - INTERVAL '2 hours', 'T-20260703-0302'
);

-- ── T-303: BullPutSpread — should produce READJUST ───────────────────────────
-- Override: niftySpot=24200, σ=0.200 → PoP≈66.3% (65–74%) → READJUST
INSERT INTO trades (
    id, user_profile_id,
    status, strategy, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'a3000001-0000-0000-0000-000000000003',
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'ACTIVE', 'BULL_PUT_SPREAD', '2026-07-07', 7,
    '[
        {"action":"SELL","strike":24000,"optionType":"PE","instrumentKey":"NSE_FO|44621"},
        {"action":"BUY", "strike":23900,"optionType":"PE","instrumentKey":"NSE_FO|44617"}
    ]'::jsonb,
    '{"netPremiumPerUnit":26.35,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":17128,"theoreticalMaxLossTotal":47873,"realExpectedLossTotal":23937}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t3ExitNiftyLevel":24000,"t2LossThreshold":11968,"t3LossThreshold":23937}'::jsonb,
    '{
        "tradeId": "a3000001-0000-0000-0000-000000000003",
        "strategy": "BULL_PUT_SPREAD",
        "spreadDirection": "CREDIT",
        "shortLeg": {"strike":24000,"optionType":"PE","action":"SELL","ltp":64.50,"instrumentKey":"NSE_FO|44621"},
        "longLeg":  {"strike":23900,"optionType":"PE","action":"BUY", "ltp":38.15,"instrumentKey":"NSE_FO|44617"},
        "actualNetPremiumPerUnit": 26.35,
        "lots": 10,
        "lotSize": 65,
        "maxProfitTotal": 17128,
        "actualMaxLossTotal": 47873,
        "slippageAlert": false,
        "slippageAmount": 0,
        "thresholds": {
            "t1WatchNiftyLevel": 24150,
            "t2ReadjustNiftyLevel": 24075,
            "t3ExitNiftyLevel": 24000,
            "t2LossThreshold": 11968,
            "t3LossThreshold": 23937
        },
        "expiryDate": "2026-07-07",
        "dte": 7
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000003-L0","instrumentKey":"NSE_FO|44621","action":"SELL","strike":24000,"optionType":"PE","quantityFilled":650,"averageFillPrice":64.50},
        {"orderId":"SIM-A3000003-L1","instrumentKey":"NSE_FO|44617","action":"BUY","strike":23900,"optionType":"PE","quantityFilled":650,"averageFillPrice":38.15}
    ]'::jsonb,
    NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours 50 minutes', NOW() - INTERVAL '2 hours', 'T-20260703-0303'
);

-- ── T-304: BullPutSpread — should produce EXIT ───────────────────────────────
-- Override: niftySpot=23950 → below short strike 24000 → T3_SHORT_STRIKE_BREACH → EXIT
-- (PoP is irrelevant; breach check fires before PoP ladder)
INSERT INTO trades (
    id, user_profile_id,
    status, strategy, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'a3000001-0000-0000-0000-000000000004',
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'ACTIVE', 'BULL_PUT_SPREAD', '2026-07-07', 7,
    '[
        {"action":"SELL","strike":24000,"optionType":"PE","instrumentKey":"NSE_FO|44621"},
        {"action":"BUY", "strike":23900,"optionType":"PE","instrumentKey":"NSE_FO|44617"}
    ]'::jsonb,
    '{"netPremiumPerUnit":26.35,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":17128,"theoreticalMaxLossTotal":47873,"realExpectedLossTotal":23937}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t3ExitNiftyLevel":24000,"t2LossThreshold":11968,"t3LossThreshold":23937}'::jsonb,
    '{
        "tradeId": "a3000001-0000-0000-0000-000000000004",
        "strategy": "BULL_PUT_SPREAD",
        "spreadDirection": "CREDIT",
        "shortLeg": {"strike":24000,"optionType":"PE","action":"SELL","ltp":64.50,"instrumentKey":"NSE_FO|44621"},
        "longLeg":  {"strike":23900,"optionType":"PE","action":"BUY", "ltp":38.15,"instrumentKey":"NSE_FO|44617"},
        "actualNetPremiumPerUnit": 26.35,
        "lots": 10,
        "lotSize": 65,
        "maxProfitTotal": 17128,
        "actualMaxLossTotal": 47873,
        "slippageAlert": false,
        "slippageAmount": 0,
        "thresholds": {
            "t1WatchNiftyLevel": 24150,
            "t2ReadjustNiftyLevel": 24075,
            "t3ExitNiftyLevel": 24000,
            "t2LossThreshold": 11968,
            "t3LossThreshold": 23937
        },
        "expiryDate": "2026-07-07",
        "dte": 7
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000004-L0","instrumentKey":"NSE_FO|44621","action":"SELL","strike":24000,"optionType":"PE","quantityFilled":650,"averageFillPrice":64.50},
        {"orderId":"SIM-A3000004-L1","instrumentKey":"NSE_FO|44617","action":"BUY","strike":23900,"optionType":"PE","quantityFilled":650,"averageFillPrice":38.15}
    ]'::jsonb,
    NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours 50 minutes', NOW() - INTERVAL '2 hours', 'T-20260703-0304'
);

-- ── T-305: BullPutSpread — VIX spike → PAUSE ─────────────────────────────────
-- Override: niftySpot=24450 (above all thresholds, fine if not for VIX)
--           vix=32.0 > 24 (Extreme) → PAUSE (auto-trading suspended)
-- VIX Extreme check fires FIRST before any price/PoP logic.
INSERT INTO trades (
    id, user_profile_id,
    status, strategy, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'a3000001-0000-0000-0000-000000000005',
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'ACTIVE', 'BULL_PUT_SPREAD', '2026-07-07', 7,
    '[
        {"action":"SELL","strike":24000,"optionType":"PE","instrumentKey":"NSE_FO|44621"},
        {"action":"BUY", "strike":23900,"optionType":"PE","instrumentKey":"NSE_FO|44617"}
    ]'::jsonb,
    '{"netPremiumPerUnit":26.35,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":17128,"theoreticalMaxLossTotal":47873,"realExpectedLossTotal":23937}'::jsonb,
    '{"spot":24350,"vix":18.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t3ExitNiftyLevel":24000,"t2LossThreshold":11968,"t3LossThreshold":23937}'::jsonb,
    '{
        "tradeId": "a3000001-0000-0000-0000-000000000005",
        "strategy": "BULL_PUT_SPREAD",
        "spreadDirection": "CREDIT",
        "shortLeg": {"strike":24000,"optionType":"PE","action":"SELL","ltp":64.50,"instrumentKey":"NSE_FO|44621"},
        "longLeg":  {"strike":23900,"optionType":"PE","action":"BUY", "ltp":38.15,"instrumentKey":"NSE_FO|44617"},
        "actualNetPremiumPerUnit": 26.35,
        "lots": 10,
        "lotSize": 65,
        "maxProfitTotal": 17128,
        "actualMaxLossTotal": 47873,
        "slippageAlert": false,
        "slippageAmount": 0,
        "thresholds": {
            "t1WatchNiftyLevel": 24150,
            "t2ReadjustNiftyLevel": 24075,
            "t3ExitNiftyLevel": 24000,
            "t2LossThreshold": 11968,
            "t3LossThreshold": 23937
        },
        "expiryDate": "2026-07-07",
        "dte": 7
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000005-L0","instrumentKey":"NSE_FO|44621","action":"SELL","strike":24000,"optionType":"PE","quantityFilled":650,"averageFillPrice":64.50},
        {"orderId":"SIM-A3000005-L1","instrumentKey":"NSE_FO|44617","action":"BUY","strike":23900,"optionType":"PE","quantityFilled":650,"averageFillPrice":38.15}
    ]'::jsonb,
    NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours 50 minutes', NOW() - INTERVAL '2 hours', 'T-20260703-0305'
);

-- ─────────────────────────────────────────────────────────────────────────────
-- BullCallSpread (T-306, T-307)
--   Short CE 24250: NSE_FO|44642  entryLtp=43.55  (SELL leg)
--   Long  CE 24100: NSE_FO|44633  entryLtp=102.45 (BUY  leg)
--   actualNetPremiumPerUnit=58.90 (debit paid), lots=2, qty=130
--   t1WatchNiftyLevel=24200 (0.5% RoC level → WATCH)
--   t2ReadjustNiftyLevel=24250 (1% RoC level → EXIT profit)
--   t2LossThreshold=3829 (50% of totalPremiumPaid: 58.90 × 65 × 2 / 2 = 3828.5 ≈ 3829)
-- ─────────────────────────────────────────────────────────────────────────────

-- ── T-306: BullCallSpread (debit) — T2 PROFIT EXIT ───────────────────────────
-- Override: niftySpot=24250 ≥ t2ReadjustNiftyLevel=24250 → EXIT (book 1% RoC profit)
-- shortLegLtp=55.00 (SELL CE 24250 risen from 43.55 with spot at 24250)
-- longLegLtp=160.00 (BUY CE 24100 risen from 102.45 with spot at 24250)
-- MTM = (160-55 - 58.90) × 2 × 65 = 46.10 × 130 = +5993 (profit, no loss stop)
INSERT INTO trades (
    id, user_profile_id,
    status, strategy, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'a3000001-0000-0000-0000-000000000006',
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'ACTIVE', 'BULL_CALL_SPREAD', '2026-07-07', 7,
    '[
        {"action":"BUY", "strike":24100,"optionType":"CE","instrumentKey":"NSE_FO|44633"},
        {"action":"SELL","strike":24250,"optionType":"CE","instrumentKey":"NSE_FO|44642"}
    ]'::jsonb,
    '{"netPremiumPerUnit":58.90,"spreadWidth":150,"lots":2,"lotSize":65,"maxProfitTotal":11843,"theoreticalMaxLossTotal":7657,"realExpectedLossTotal":3829}'::jsonb,
    '{"spot":24050,"vix":15.20,"ivRegime":"FAIR","bias":"BULLISH","strength":"EXTREME","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24200,"t2ReadjustNiftyLevel":24250,"t2LossThreshold":3829}'::jsonb,
    '{
        "tradeId": "a3000001-0000-0000-0000-000000000006",
        "strategy": "BULL_CALL_SPREAD",
        "spreadDirection": "DEBIT",
        "shortLeg": {"strike":24250,"optionType":"CE","action":"SELL","ltp":43.55,"instrumentKey":"NSE_FO|44642"},
        "longLeg":  {"strike":24100,"optionType":"CE","action":"BUY", "ltp":102.45,"instrumentKey":"NSE_FO|44633"},
        "actualNetPremiumPerUnit": 58.90,
        "lots": 2,
        "lotSize": 65,
        "maxProfitTotal": 11843,
        "actualMaxLossTotal": 7657,
        "slippageAlert": false,
        "slippageAmount": 0,
        "thresholds": {
            "t1WatchNiftyLevel": 24200,
            "t2ReadjustNiftyLevel": 24250,
            "t2LossThreshold": 3829
        },
        "expiryDate": "2026-07-07",
        "dte": 7
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000006-L0","instrumentKey":"NSE_FO|44633","action":"BUY","strike":24100,"optionType":"CE","quantityFilled":130,"averageFillPrice":102.45},
        {"orderId":"SIM-A3000006-L1","instrumentKey":"NSE_FO|44642","action":"SELL","strike":24250,"optionType":"CE","quantityFilled":130,"averageFillPrice":43.55}
    ]'::jsonb,
    NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours 50 minutes', NOW() - INTERVAL '2 hours', 'T-20260703-0306'
);

-- ── T-307: BullCallSpread (debit) — T3 LOSS CUT EXIT ─────────────────────────
-- Override: niftySpot=24000, shortLegLtp=2.00, longLegLtp=10.00
-- Both calls deeply OTM with spot=24000, DTE=4 — minimal time value remaining.
-- currentNetPremium (DEBIT) = longLegLtp - shortLegLtp = 10 - 2 = 8.00
-- MTM = (8.00 - 58.90) × 2 × 65 = -50.90 × 130 = -6617
-- hasBreachedLossThreshold(-6617, 3829) → -6617 ≤ -3829 → EXIT ✓
INSERT INTO trades (
    id, user_profile_id,
    status, strategy, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'a3000001-0000-0000-0000-000000000007',
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'ACTIVE', 'BULL_CALL_SPREAD', '2026-07-07', 7,
    '[
        {"action":"BUY", "strike":24100,"optionType":"CE","instrumentKey":"NSE_FO|44633"},
        {"action":"SELL","strike":24250,"optionType":"CE","instrumentKey":"NSE_FO|44642"}
    ]'::jsonb,
    '{"netPremiumPerUnit":58.90,"spreadWidth":150,"lots":2,"lotSize":65,"maxProfitTotal":11843,"theoreticalMaxLossTotal":7657,"realExpectedLossTotal":3829}'::jsonb,
    '{"spot":24050,"vix":15.20,"ivRegime":"FAIR","bias":"BULLISH","strength":"EXTREME","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24200,"t2ReadjustNiftyLevel":24250,"t2LossThreshold":3829}'::jsonb,
    '{
        "tradeId": "a3000001-0000-0000-0000-000000000007",
        "strategy": "BULL_CALL_SPREAD",
        "spreadDirection": "DEBIT",
        "shortLeg": {"strike":24250,"optionType":"CE","action":"SELL","ltp":43.55,"instrumentKey":"NSE_FO|44642"},
        "longLeg":  {"strike":24100,"optionType":"CE","action":"BUY", "ltp":102.45,"instrumentKey":"NSE_FO|44633"},
        "actualNetPremiumPerUnit": 58.90,
        "lots": 2,
        "lotSize": 65,
        "maxProfitTotal": 11843,
        "actualMaxLossTotal": 7657,
        "slippageAlert": false,
        "slippageAmount": 0,
        "thresholds": {
            "t1WatchNiftyLevel": 24200,
            "t2ReadjustNiftyLevel": 24250,
            "t2LossThreshold": 3829
        },
        "expiryDate": "2026-07-07",
        "dte": 7
    }'::jsonb,
    '[
        {"orderId":"SIM-A3000007-L0","instrumentKey":"NSE_FO|44633","action":"BUY","strike":24100,"optionType":"CE","quantityFilled":130,"averageFillPrice":102.45},
        {"orderId":"SIM-A3000007-L1","instrumentKey":"NSE_FO|44642","action":"SELL","strike":24250,"optionType":"CE","quantityFilled":130,"averageFillPrice":43.55}
    ]'::jsonb,
    NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours 50 minutes', NOW() - INTERVAL '2 hours', 'T-20260703-0307'
);

SELECT id, strategy, status, expiry_date FROM trades WHERE id::text LIKE 'a3000001-%' ORDER BY id;
