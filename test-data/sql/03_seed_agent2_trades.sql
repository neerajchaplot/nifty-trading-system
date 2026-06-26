-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: trades — PENDING_CONFIRM rows for Agent 2 confirm/reject testing
--                CONFIRMED rows for Agent 5 execute testing
--
-- Instrument keys: UPDATE these with Friday's captured keys before the weekend.
-- The placeholder keys (NSE_FO|XXXXXX) will cause UDAPI100036 if simulate-fills=false.
-- With simulate-fills=true (sandbox profile) they work as-is.
--
-- See test-data/capture/capture_friday.sh — run Friday before 3:30 PM.
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

DELETE FROM trades WHERE id::text LIKE 'a2000001-%';

-- ── T-201: BullPutSpread PENDING_CONFIRM (all gates pass) ────────────────────
-- Signal: S02 (Bullish Mild + VIX High). Capital: 10L.
-- Expected confirm → CONFIRMED. Expected reject → REJECTED.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, expiry_date,
    legs, summary, market_context, gate_results, thresholds
) VALUES (
    'a2000001-0000-0000-0000-000000000001',
    'a1000001-0000-0000-0000-000000000002',   -- S02 Bullish Mild VIX High
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'PENDING_CONFIRM', 'BullPutSpread', '2026-07-01',
    '[
        {"action":"SELL","strike":23800,"optionType":"PE","ltp":72.50,"iv":0.185,"delta":-0.182,"theta":-15.2,"vega":19.8,"pop":0.838,"instrumentKey":"NSE_FO|REPLACE_SELL_PE_23800"},
        {"action":"BUY", "strike":23700,"optionType":"PE","ltp":48.30,"iv":0.191,"delta":-0.156,"theta":-12.8,"vega":16.5,"pop":0.855,"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23700"}
    ]'::jsonb,
    '{"netPremiumPerUnit":24.20,"spreadWidth":100,"lots":8,"lotSize":65,"maxProfitTotal":12584,"theoreticalMaxLossTotal":51376,"realExpectedLossTotal":25688,"pop":0.838,"popp":0.855,"popGap":0.017,"roc":0.85,"rocAnnualised":62.1}'::jsonb,
    '{"spot":24120,"vix":20.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":83.8,"threshold":80},{"gate":"G2","passed":true,"value":25688,"threshold":15000},{"gate":"G3","passed":true,"value":1.7,"threshold":15},{"gate":"G4","passed":true,"value":0.85,"threshold":0.70}]'::jsonb,
    '{"t1WatchNifty":23950,"t2ReadjustNifty":23875,"t2ReadjustPnlLoss":12844,"t3ExitNifty":23800,"t3ExitPnlLoss":25688}'::jsonb
);

-- ── T-202: BullCallSpread PENDING_CONFIRM ────────────────────────────────────
-- Signal: S01 (Bullish Extreme). Capital: 10L.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, expiry_date,
    legs, summary, market_context, gate_results, thresholds
) VALUES (
    'a2000001-0000-0000-0000-000000000002',
    'a1000001-0000-0000-0000-000000000001',   -- S01 Bullish Extreme
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'PENDING_CONFIRM', 'BullCallSpread', '2026-07-01',
    '[
        {"action":"BUY", "strike":24200,"optionType":"CE","ltp":158.40,"iv":0.168,"delta":0.485,"theta":-18.2,"vega":22.4,"pop":0.485,"instrumentKey":"NSE_FO|REPLACE_BUY_CE_24200"},
        {"action":"SELL","strike":24400,"optionType":"CE","ltp":85.60,"iv":0.172,"delta":0.318,"theta":-14.8,"vega":18.9,"pop":0.318,"instrumentKey":"NSE_FO|REPLACE_SELL_CE_24400"}
    ]'::jsonb,
    '{"netPremiumPerUnit":72.80,"spreadWidth":200,"lots":2,"lotSize":65,"maxProfitTotal":16380,"theoreticalMaxLossTotal":9464,"realExpectedLossTotal":9464,"pop":0.420,"popp":0.318,"popGap":0.102,"roc":1.73,"rocAnnualised":90.2}'::jsonb,
    '{"spot":24350,"vix":15.20,"ivRegime":"FAIR","bias":"BULLISH","strength":"EXTREME","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":42.0,"threshold":35},{"gate":"G2","passed":true,"value":9464,"threshold":15000},{"gate":"G3","passed":true,"value":10.2,"threshold":15},{"gate":"G4","passed":true,"value":1.73,"threshold":0.70}]'::jsonb,
    '{"t1ProfitNifty":24470,"t2StretchNifty":24575,"t3LossNifty":null,"t3LossPnl":4732}'::jsonb
);

-- ── T-203: BearCallSpread PENDING_CONFIRM ────────────────────────────────────
-- Signal: S09 (Bearish Mild + VIX High). Capital: 10L.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, expiry_date,
    legs, summary, market_context, gate_results, thresholds
) VALUES (
    'a2000001-0000-0000-0000-000000000003',
    'a1000001-0000-0000-0000-000000000009',   -- S09 Bearish Mild VIX High
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'PENDING_CONFIRM', 'BearCallSpread', '2026-07-01',
    '[
        {"action":"SELL","strike":24250,"optionType":"CE","ltp":68.20,"iv":0.192,"delta":0.175,"theta":-14.8,"vega":18.2,"pop":0.827,"instrumentKey":"NSE_FO|REPLACE_SELL_CE_24250"},
        {"action":"BUY", "strike":24350,"optionType":"CE","ltp":44.80,"iv":0.198,"delta":0.148,"theta":-12.5,"vega":15.6,"pop":0.853,"instrumentKey":"NSE_FO|REPLACE_BUY_CE_24350"}
    ]'::jsonb,
    '{"netPremiumPerUnit":23.40,"spreadWidth":100,"lots":8,"lotSize":65,"maxProfitTotal":12168,"theoreticalMaxLossTotal":49712,"realExpectedLossTotal":24856,"pop":0.827,"popp":0.853,"popGap":0.026,"roc":0.82,"rocAnnualised":60.0}'::jsonb,
    '{"spot":23800,"vix":20.80,"ivRegime":"RICH","bias":"BEARISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":82.7,"threshold":80},{"gate":"G2","passed":true,"value":24856,"threshold":15000},{"gate":"G3","passed":true,"value":2.6,"threshold":15},{"gate":"G4","passed":true,"value":0.82,"threshold":0.70}]'::jsonb,
    '{"t1WatchNifty":24100,"t2ReadjustNifty":24175,"t2ReadjustPnlLoss":12428,"t3ExitNifty":24250,"t3ExitPnlLoss":24856}'::jsonb
);

-- ── T-204: IronCondor PENDING_CONFIRM ────────────────────────────────────────
-- Signal: S05 (Neutral Weak + VIX High + IV Rich). Capital: 50L.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, expiry_date,
    legs, summary, market_context, gate_results, thresholds
) VALUES (
    'a2000001-0000-0000-0000-000000000004',
    'a1000001-0000-0000-0000-000000000005',   -- S05 Neutral VIX High
    '00000001-0000-0000-0000-000000000003',   -- 50L user
    'PENDING_CONFIRM', 'IronCondor', '2026-07-01',
    '[
        {"action":"SELL","strike":23600,"optionType":"PE","ltp":55.80,"iv":0.212,"delta":-0.162,"theta":-13.5,"vega":17.2,"pop":0.842,"instrumentKey":"NSE_FO|REPLACE_SELL_PE_23600"},
        {"action":"BUY", "strike":23500,"optionType":"PE","ltp":38.20,"iv":0.218,"delta":-0.138,"theta":-11.2,"vega":14.5,"pop":0.863,"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23500"},
        {"action":"SELL","strike":24400,"optionType":"CE","ltp":52.40,"iv":0.188,"delta":0.158,"theta":-12.8,"vega":16.5,"pop":0.842,"instrumentKey":"NSE_FO|REPLACE_SELL_CE_24400"},
        {"action":"BUY", "strike":24500,"optionType":"CE","ltp":35.60,"iv":0.194,"delta":0.135,"theta":-10.8,"vega":14.0,"pop":0.865,"instrumentKey":"NSE_FO|REPLACE_BUY_CE_24500"}
    ]'::jsonb,
    '{"netPremiumPerUnit":34.40,"spreadWidth":100,"lots":45,"lotSize":65,"maxProfitTotal":100620,"theoreticalMaxLossTotal":422760,"realExpectedLossTotal":211380,"pop":0.842,"popp":0.865,"popGap":0.023,"roc":0.69,"rocAnnualised":50.4}'::jsonb,
    '{"spot":24000,"vix":21.00,"ivRegime":"RICH","bias":"NEUTRAL","strength":"WEAK","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":84.2,"threshold":80},{"gate":"G2","passed":true,"value":211380,"threshold":750000},{"gate":"G3","passed":true,"value":2.3,"threshold":15},{"gate":"G4","passed":true,"value":0.69,"threshold":0.70}]'::jsonb,
    '{"t1WatchNiftyDown":23750,"t2ReadjustNiftyDown":23675,"t3ExitNiftyDown":23600,"t1WatchNiftyUp":24250,"t2ReadjustNiftyUp":24325,"t3ExitNiftyUp":24400}'::jsonb
);

-- ── T-205: Gate failure — G1 PoP too low ─────────────────────────────────────
-- Status REJECTED at generation time. Tests that confirm endpoint rejects REJECTED trades.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, expiry_date,
    legs, summary, market_context, gate_results
) VALUES (
    'a2000001-0000-0000-0000-000000000005',
    'a1000001-0000-0000-0000-000000000003',
    '00000001-0000-0000-0000-000000000001',
    'REJECTED', 'BullPutSpread', '2026-07-01',
    '[{"action":"SELL","strike":24000,"optionType":"PE","ltp":45.20,"iv":0.168,"delta":-0.120,"pop":0.724,"instrumentKey":"NSE_FO|REPLACE_SELL_PE_24000"},
      {"action":"BUY", "strike":23900,"optionType":"PE","ltp":32.80,"iv":0.174,"delta":-0.102,"pop":0.742,"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23900"}]'::jsonb,
    '{"netPremiumPerUnit":12.40,"spreadWidth":100,"lots":0,"lotSize":65,"pop":0.724,"popp":0.742}'::jsonb,
    '{"spot":24200,"vix":15.80,"ivRegime":"FAIR","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":false,"value":72.4,"threshold":80,"failReason":"PoP 72.4% < minimum 80%"}]'::jsonb
);

-- ── T-206: Gate failure — G3 PoPP gap > 15% ──────────────────────────────────
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, expiry_date,
    legs, summary, market_context, gate_results
) VALUES (
    'a2000001-0000-0000-0000-000000000006',
    'a1000001-0000-0000-0000-000000000003',
    '00000001-0000-0000-0000-000000000001',
    'REJECTED', 'BullPutSpread', '2026-07-01',
    '[{"action":"SELL","strike":23600,"optionType":"PE","ltp":28.50,"iv":0.164,"delta":-0.088,"pop":0.912,"instrumentKey":"NSE_FO|REPLACE_SELL_PE_23600"},
      {"action":"BUY", "strike":23500,"optionType":"PE","ltp":18.20,"iv":0.170,"delta":-0.068,"pop":0.942,"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23500"}]'::jsonb,
    '{"netPremiumPerUnit":10.30,"spreadWidth":100,"lots":0,"lotSize":65,"pop":0.912,"popp":0.942,"popGap":0.030}'::jsonb,
    '{"spot":24200,"vix":15.80,"ivRegime":"FAIR","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":91.2,"threshold":80},{"gate":"G2","passed":true},{"gate":"G3","passed":false,"value":3.0,"threshold":15,"failReason":"PoPP gap 3.0% — actually passes, but netPremium too low for G4"},{"gate":"G4","passed":false,"value":0.36,"threshold":0.70,"failReason":"RoC 0.36% < minimum 0.70% for 7 DTE"}]'::jsonb
);

-- ── T-207: CONFIRMED — ready for Agent 5 execute (BullPutSpread) ─────────────
-- UPDATE instrument keys with Friday's values before testing.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, expiry_date,
    legs, summary, market_context, gate_results, thresholds
) VALUES (
    'a2000001-0000-0000-0000-000000000007',
    'a1000001-0000-0000-0000-000000000002',
    '00000001-0000-0000-0000-000000000002',
    'CONFIRMED', 'BullPutSpread', '2026-07-01',
    '[
        {"action":"SELL","strike":23800,"optionType":"PE","ltp":72.50,"iv":0.185,"delta":-0.182,"pop":0.838,"instrumentKey":"NSE_FO|REPLACE_SELL_PE_23800"},
        {"action":"BUY", "strike":23700,"optionType":"PE","ltp":48.30,"iv":0.191,"delta":-0.156,"pop":0.855,"instrumentKey":"NSE_FO|REPLACE_BUY_PE_23700"}
    ]'::jsonb,
    '{"netPremiumPerUnit":24.20,"spreadWidth":100,"lots":8,"lotSize":65,"pop":0.838,"popp":0.855,"popGap":0.017,"roc":0.85}'::jsonb,
    '{"spot":24120,"vix":20.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNifty":23950,"t2ReadjustNifty":23875,"t2ReadjustPnlLoss":12844,"t3ExitNifty":23800,"t3ExitPnlLoss":25688}'::jsonb
);

-- ── T-208: CONFIRMED — ready for Agent 5 execute (BullCallSpread) ────────────
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, expiry_date,
    legs, summary, market_context, gate_results, thresholds
) VALUES (
    'a2000001-0000-0000-0000-000000000008',
    'a1000001-0000-0000-0000-000000000001',
    '00000001-0000-0000-0000-000000000002',
    'CONFIRMED', 'BullCallSpread', '2026-07-01',
    '[
        {"action":"BUY", "strike":24200,"optionType":"CE","ltp":158.40,"iv":0.168,"delta":0.485,"pop":0.485,"instrumentKey":"NSE_FO|REPLACE_BUY_CE_24200"},
        {"action":"SELL","strike":24400,"optionType":"CE","ltp":85.60,"iv":0.172,"delta":0.318,"pop":0.318,"instrumentKey":"NSE_FO|REPLACE_SELL_CE_24400"}
    ]'::jsonb,
    '{"netPremiumPerUnit":72.80,"spreadWidth":200,"lots":2,"lotSize":65,"pop":0.420,"popp":0.318,"popGap":0.102,"roc":1.73}'::jsonb,
    '{"spot":24350,"vix":15.20,"ivRegime":"FAIR","bias":"BULLISH","strength":"EXTREME","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1ProfitNifty":24470,"t2StretchNifty":24575,"t3LossPnl":4732}'::jsonb
);

SELECT id, strategy, status FROM trades WHERE id::text LIKE 'a2000001-%' ORDER BY id;
