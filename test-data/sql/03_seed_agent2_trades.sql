-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: trades — PENDING_CONFIRM rows for Agent 2 confirm/reject testing
--                CONFIRMED rows for Agent 5 execute testing
--
-- Instrument keys: UPDATED with Friday 2026-06-27 capture.
-- Expiry: 2026-06-30 (next Tuesday). ATM=24050 at capture time.
--
-- Strike reference (from capture_friday.sh output):
--   23850 PE = NSE_FO|79714  LTP=29.45
--   23900 PE = NSE_FO|79723  LTP=38.15   ← near OTM PE
--   23950 PE = NSE_FO|79729  LTP=49.50
--   24000 PE = NSE_FO|71473  LTP=64.50   ← short strike (BullPutSpread)
--   24050 PE = NSE_FO|79731  LTP=82.80   ← ATM PE
--   24100 CE = NSE_FO|79732  LTP=102.45  ← long strike (BullCallSpread)
--   24150 CE = NSE_FO|79734  LTP=78.10   ← short strike (BearCallSpread)
--   24200 CE = NSE_FO|79736  LTP=58.70
--   24250 CE = NSE_FO|79738  LTP=43.55
--
-- NOTE: These are test scenarios using near-ATM strikes. In production,
-- BullPutSpread would use strikes ~400-500 pts OTM for PoP ≥ 80%.
-- Gate results are pre-set — confirm endpoint reads stored values, not recalculated.
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

DELETE FROM trades WHERE id::text LIKE 'a2000001-%';

-- ── T-201: BullPutSpread PENDING_CONFIRM (all gates pass) ────────────────────
-- Signal: S02 (Bullish Mild + VIX High + IV Rich). Capital: 10L.
-- Short: SELL PE 24000. Long: BUY PE 23900. Net: 26.35/unit.
-- T1=24150 (short+150), T2=24075 (short+75), T3=24000 (breach)
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000001',
    'a1000001-0000-0000-0000-000000000002',   -- S02 Bullish Mild VIX High
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'PENDING_CONFIRM', 'BULL_PUT_SPREAD', 'CREDIT', '2026-06-30', 7,
    '[
        {"action":"SELL","strike":24000,"optionType":"PE","ltp":64.50,"iv":0.192,"delta":-0.205,"theta":-16.8,"vega":20.2,"pop":0.821,"instrumentKey":"NSE_FO|71473"},
        {"action":"BUY", "strike":23900,"optionType":"PE","ltp":38.15,"iv":0.198,"delta":-0.172,"theta":-14.4,"vega":17.5,"pop":0.844,"instrumentKey":"NSE_FO|79723"}
    ]'::jsonb,
    '{"netPremiumPerUnit":26.35,"spreadWidth":100,"lots":8,"lotSize":65,"maxProfitTotal":13702,"theoreticalMaxLossTotal":38298,"realExpectedLossTotal":19149,"pop":0.821,"popp":0.844,"popGap":0.023,"roc":1.37,"rocAnnualised":71.7}'::jsonb,
    '{"spot":24050,"vix":20.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":82.1,"threshold":80},{"gate":"G2","passed":true,"value":19149,"threshold":15000},{"gate":"G3","passed":true,"value":2.3,"threshold":15},{"gate":"G4","passed":true,"value":1.37,"threshold":0.70}]'::jsonb,
    '{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t2LossThreshold":9575,"t3ExitNiftyLevel":24000,"t3LossThreshold":19149}'::jsonb,
    NOW() - INTERVAL '10 minutes', NOW() + INTERVAL '20 minutes', 'T-20260627-0201'
);

-- ── T-202: BullCallSpread PENDING_CONFIRM ────────────────────────────────────
-- Signal: S01 (Bullish Extreme). Capital: 10L.
-- Long: BUY CE 24100. Short: SELL CE 24250. Net debit: 58.90/unit.
-- Break-even: 24100+58.90=24158.90. T1 profit target: Nifty at 24200.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000002',
    'a1000001-0000-0000-0000-000000000001',   -- S01 Bullish Extreme
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'PENDING_CONFIRM', 'BULL_CALL_SPREAD', 'DEBIT', '2026-06-30', 7,
    '[
        {"action":"BUY", "strike":24100,"optionType":"CE","ltp":102.45,"iv":0.182,"delta":0.428,"theta":-18.5,"vega":21.8,"pop":0.428,"instrumentKey":"NSE_FO|79732"},
        {"action":"SELL","strike":24250,"optionType":"CE","ltp":43.55,"iv":0.192,"delta":0.240,"theta":-12.2,"vega":15.4,"pop":0.240,"instrumentKey":"NSE_FO|79738"}
    ]'::jsonb,
    '{"netPremiumPerUnit":58.90,"spreadWidth":150,"lots":2,"lotSize":65,"maxProfitTotal":11843,"theoreticalMaxLossTotal":7657,"realExpectedLossTotal":3829,"pop":0.428,"popp":0.240,"popGap":0.188,"roc":1.18,"rocAnnualised":61.7}'::jsonb,
    '{"spot":24050,"vix":15.20,"ivRegime":"FAIR","bias":"BULLISH","strength":"EXTREME","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":42.8,"threshold":35,"note":"BEP breakeven PoP for debit spread"},{"gate":"G2","passed":true,"value":3829,"threshold":15000},{"gate":"G3","passed":true,"value":10.5,"threshold":15,"note":"pre-set for test"},{"gate":"G4","passed":true,"value":1.18,"threshold":0.70}]'::jsonb,
    '{"t1WatchNiftyLevel":24200,"t2ReadjustNiftyLevel":24250,"t2LossThreshold":3829}'::jsonb,
    NOW() - INTERVAL '10 minutes', NOW() + INTERVAL '20 minutes', 'T-20260627-0202'
);

-- ── T-203: BearCallSpread PENDING_CONFIRM ────────────────────────────────────
-- Signal: S09 (Bearish Mild + VIX High). Capital: 10L.
-- Short: SELL CE 24150. Long: BUY CE 24250. Net: 34.55/unit.
-- T1=24000 (short-150), T2=24075 (short-75), T3=24150 (breach)
-- Gate values pre-set (test scenario — confirm/execute flow test).
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000003',
    'a1000001-0000-0000-0000-000000000009',   -- S09 Bearish Mild VIX High
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'PENDING_CONFIRM', 'BEAR_CALL_SPREAD', 'CREDIT', '2026-06-30', 7,
    '[
        {"action":"SELL","strike":24150,"optionType":"CE","ltp":78.10,"iv":0.188,"delta":0.320,"theta":-17.2,"vega":20.8,"pop":0.827,"instrumentKey":"NSE_FO|79734"},
        {"action":"BUY", "strike":24250,"optionType":"CE","ltp":43.55,"iv":0.194,"delta":0.248,"theta":-13.5,"vega":16.8,"pop":0.852,"instrumentKey":"NSE_FO|79738"}
    ]'::jsonb,
    '{"netPremiumPerUnit":34.55,"spreadWidth":100,"lots":8,"lotSize":65,"maxProfitTotal":17966,"theoreticalMaxLossTotal":34034,"realExpectedLossTotal":17017,"pop":0.827,"popp":0.852,"popGap":0.025,"roc":1.80,"rocAnnualised":93.9}'::jsonb,
    '{"spot":24050,"vix":20.80,"ivRegime":"RICH","bias":"BEARISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":82.7,"threshold":80,"note":"pre-set for test"},{"gate":"G2","passed":true,"value":17017,"threshold":15000},{"gate":"G3","passed":true,"value":2.5,"threshold":15},{"gate":"G4","passed":true,"value":1.80,"threshold":0.70}]'::jsonb,
    '{"t1WatchNiftyLevel":24000,"t2ReadjustNiftyLevel":24075,"t2LossThreshold":8509,"t3ExitNiftyLevel":24150,"t3LossThreshold":17017}'::jsonb,
    NOW() - INTERVAL '10 minutes', NOW() + INTERVAL '20 minutes', 'T-20260627-0203'
);

-- ── T-204: IronCondor PENDING_CONFIRM ────────────────────────────────────────
-- Signal: S05 (Neutral Weak + VIX High + IV Rich). Capital: 50L.
-- PE spread: SELL PE 23900 / BUY PE 23850. Net PE: 8.70/unit.
-- CE spread: SELL CE 24150 / BUY CE 24250. Net CE: 34.55/unit.
-- Total net: 43.25/unit. Lots: 40.
-- T1 down=24050 (PE short+150), T1 up=24000 (CE short-150)
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000004',
    'a1000001-0000-0000-0000-000000000005',   -- S05 Neutral VIX High
    '00000001-0000-0000-0000-000000000003',   -- 50L user
    'PENDING_CONFIRM', 'IRON_CONDOR', 'CREDIT', '2026-06-30', 7,
    '[
        {"action":"SELL","strike":23900,"optionType":"PE","ltp":38.15,"iv":0.205,"delta":-0.180,"theta":-15.2,"vega":18.8,"pop":0.820,"instrumentKey":"NSE_FO|79723"},
        {"action":"BUY", "strike":23850,"optionType":"PE","ltp":29.45,"iv":0.211,"delta":-0.152,"theta":-12.8,"vega":15.9,"pop":0.848,"instrumentKey":"NSE_FO|79714"},
        {"action":"SELL","strike":24150,"optionType":"CE","ltp":78.10,"iv":0.188,"delta":0.320,"theta":-17.2,"vega":20.8,"pop":0.820,"instrumentKey":"NSE_FO|79734"},
        {"action":"BUY", "strike":24250,"optionType":"CE","ltp":43.55,"iv":0.194,"delta":0.248,"theta":-13.5,"vega":16.8,"pop":0.848,"instrumentKey":"NSE_FO|79738"}
    ]'::jsonb,
    '{"netPremiumPerUnit":43.25,"spreadWidth":100,"lots":40,"lotSize":65,"maxProfitTotal":112450,"theoreticalMaxLossTotal":147550,"realExpectedLossTotal":73775,"pop":0.820,"popp":0.848,"popGap":0.028,"roc":2.25,"rocAnnualised":117.4}'::jsonb,
    '{"spot":24050,"vix":21.00,"ivRegime":"RICH","bias":"NEUTRAL","strength":"WEAK","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":82.0,"threshold":80,"note":"PE side PoP; pre-set for test"},{"gate":"G2","passed":true,"value":73775,"threshold":750000},{"gate":"G3","passed":true,"value":2.8,"threshold":15},{"gate":"G4","passed":true,"value":2.25,"threshold":0.70}]'::jsonb,
    '{"t1WatchNiftyDown":24050,"t2ReadjustNiftyDown":23975,"t3ExitNiftyDown":23900,"t1WatchNiftyUp":24000,"t2ReadjustNiftyUp":24075,"t3ExitNiftyUp":24150}'::jsonb,
    NOW() - INTERVAL '10 minutes', NOW() + INTERVAL '20 minutes', 'T-20260627-0204'
);

-- ── T-205: Gate failure — G1 PoP too low ─────────────────────────────────────
-- Near-ATM short strike (24050). PoP = 50% (ATM) < 80% min → G1 FAIL.
-- Status REJECTED. Verifies confirm endpoint rejects REJECTED trades.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000005',
    'a1000001-0000-0000-0000-000000000003',
    '00000001-0000-0000-0000-000000000001',
    'REJECTED', 'BULL_PUT_SPREAD', 'CREDIT', '2026-06-30', 7,
    '[
        {"action":"SELL","strike":24050,"optionType":"PE","ltp":82.80,"iv":0.180,"delta":-0.500,"pop":0.500,"instrumentKey":"NSE_FO|79731"},
        {"action":"BUY", "strike":23950,"optionType":"PE","ltp":49.50,"iv":0.186,"delta":-0.380,"pop":0.620,"instrumentKey":"NSE_FO|79729"}
    ]'::jsonb,
    '{"netPremiumPerUnit":33.30,"spreadWidth":100,"lots":0,"lotSize":65,"pop":0.500,"popp":0.620}'::jsonb,
    '{"spot":24050,"vix":15.80,"ivRegime":"FAIR","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":false,"value":50.0,"threshold":80,"failReason":"PoP 50.0% < minimum 80% — short strike 24050 is ATM"}]'::jsonb,
    '{}'::jsonb,
    NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '10 minutes', 'T-20260627-0205'
);

-- ── T-206: Gate failure — G4 RoC too low ─────────────────────────────────────
-- Far-OTM spread (23900/23850, width=50) gives only 8.70 net premium.
-- maxProfit = 8.70 × 65 × 8 = 4524. RoC = 4524 / 1,000,000 = 0.45% < 0.70% → G4 FAIL.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000006',
    'a1000001-0000-0000-0000-000000000003',
    '00000001-0000-0000-0000-000000000001',
    'REJECTED', 'BULL_PUT_SPREAD', 'CREDIT', '2026-06-30', 7,
    '[
        {"action":"SELL","strike":23900,"optionType":"PE","ltp":38.15,"iv":0.205,"delta":-0.180,"pop":0.820,"instrumentKey":"NSE_FO|79723"},
        {"action":"BUY", "strike":23850,"optionType":"PE","ltp":29.45,"iv":0.211,"delta":-0.152,"pop":0.848,"instrumentKey":"NSE_FO|79714"}
    ]'::jsonb,
    '{"netPremiumPerUnit":8.70,"spreadWidth":50,"lots":0,"lotSize":65,"pop":0.820,"popp":0.848,"popGap":0.028,"roc":0.45}'::jsonb,
    '{"spot":24050,"vix":15.80,"ivRegime":"FAIR","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":82.0,"threshold":80},{"gate":"G2","passed":true},{"gate":"G3","passed":true,"value":2.8,"threshold":15},{"gate":"G4","passed":false,"value":0.45,"threshold":0.70,"failReason":"RoC 0.45% < minimum 0.70% for 7 DTE"}]'::jsonb,
    '{}'::jsonb,
    NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '10 minutes', 'T-20260627-0206'
);

-- ── T-207: CONFIRMED — ready for Agent 5 execute (BullPutSpread) ─────────────
-- SELL PE 24000 / BUY PE 23900. Real keys from Friday capture.
-- lots=8, qty=520. expectedNet=26.35. Agent 5 simulate-fills → no slippage.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000007',
    'a1000001-0000-0000-0000-000000000002',
    '00000001-0000-0000-0000-000000000002',
    'CONFIRMED', 'BULL_PUT_SPREAD', 'CREDIT', '2026-06-30', 7,
    '[
        {"action":"SELL","strike":24000,"optionType":"PE","ltp":64.50,"iv":0.192,"delta":-0.205,"pop":0.821,"instrumentKey":"NSE_FO|71473"},
        {"action":"BUY", "strike":23900,"optionType":"PE","ltp":38.15,"iv":0.198,"delta":-0.172,"pop":0.844,"instrumentKey":"NSE_FO|79723"}
    ]'::jsonb,
    '{"netPremiumPerUnit":26.35,"spreadWidth":100,"lots":8,"lotSize":65,"pop":0.821,"popp":0.844,"popGap":0.023,"roc":1.37}'::jsonb,
    '{"spot":24050,"vix":20.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t2LossThreshold":9575,"t3ExitNiftyLevel":24000,"t3LossThreshold":19149}'::jsonb,
    NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '5 minutes', 'T-20260627-0207'
);

-- ── T-208: CONFIRMED — ready for Agent 5 execute (BullCallSpread) ────────────
-- BUY CE 24100 / SELL CE 24250. Real keys from Friday capture.
-- lots=2, qty=130. expectedNetDebit=-58.90. Verifies no false slippage on debit spread.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000008',
    'a1000001-0000-0000-0000-000000000001',
    '00000001-0000-0000-0000-000000000002',
    'CONFIRMED', 'BULL_CALL_SPREAD', 'DEBIT', '2026-06-30', 7,
    '[
        {"action":"BUY", "strike":24100,"optionType":"CE","ltp":102.45,"iv":0.182,"delta":0.428,"pop":0.428,"instrumentKey":"NSE_FO|79732"},
        {"action":"SELL","strike":24250,"optionType":"CE","ltp":43.55,"iv":0.192,"delta":0.240,"pop":0.240,"instrumentKey":"NSE_FO|79738"}
    ]'::jsonb,
    '{"netPremiumPerUnit":58.90,"spreadWidth":150,"lots":2,"lotSize":65,"pop":0.428,"popp":0.240,"popGap":0.188,"roc":1.18}'::jsonb,
    '{"spot":24050,"vix":15.20,"ivRegime":"FAIR","bias":"BULLISH","strength":"EXTREME","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24200,"t2ReadjustNiftyLevel":24250,"t2LossThreshold":3829}'::jsonb,
    NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '5 minutes', 'T-20260627-0208'
);

-- ── T-209: BullPutSpread PENDING_CONFIRM — S03 (Bullish Mild + VIX Normal + IV Fair) ─────────
-- Differentiator from T-201: market_context ivRegime=FAIR (not RICH) and vix=15.80 (NORMAL).
-- Strategy matrix: Bullish Mild + VIX Normal + IV Fair → BullPutSpread (credit).
-- Same result as T-201 but under different VIX/IV conditions — tests both VIX regimes trigger
-- BullPutSpread correctly. Same strikes/instrument keys as T-201 (reuses Friday capture data).
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000009',
    'a1000001-0000-0000-0000-000000000003',   -- S03 Bullish Mild VIX Normal
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'PENDING_CONFIRM', 'BULL_PUT_SPREAD', 'CREDIT', '2026-06-30', 7,
    '[
        {"action":"SELL","strike":24000,"optionType":"PE","ltp":64.50,"iv":0.192,"delta":-0.205,"theta":-16.8,"vega":20.2,"pop":0.821,"instrumentKey":"NSE_FO|71473"},
        {"action":"BUY", "strike":23900,"optionType":"PE","ltp":38.15,"iv":0.198,"delta":-0.172,"theta":-14.4,"vega":17.5,"pop":0.844,"instrumentKey":"NSE_FO|79723"}
    ]'::jsonb,
    '{"netPremiumPerUnit":26.35,"spreadWidth":100,"lots":8,"lotSize":65,"maxProfitTotal":13702,"theoreticalMaxLossTotal":38298,"realExpectedLossTotal":19149,"pop":0.821,"popp":0.844,"popGap":0.023,"roc":1.37,"rocAnnualised":71.7}'::jsonb,
    '{"spot":24050,"vix":15.80,"ivRegime":"FAIR","bias":"BULLISH","strength":"MILD","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":82.1,"threshold":80},{"gate":"G2","passed":true,"value":19149,"threshold":15000},{"gate":"G3","passed":true,"value":2.3,"threshold":15},{"gate":"G4","passed":true,"value":1.37,"threshold":0.70}]'::jsonb,
    '{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t2LossThreshold":9575,"t3ExitNiftyLevel":24000,"t3LossThreshold":19149}'::jsonb,
    NOW() - INTERVAL '10 minutes', NOW() + INTERVAL '20 minutes', 'T-20260627-0209'
);

-- ── T-210: ShortStrangle PENDING_CONFIRM — S06 (Neutral Weak + VIX Normal + IV Rich) ──────────
-- IV Rich forced in market_context (live IV/HV ratio not available at seed time).
-- Strategy matrix: Neutral Weak + VIX Normal + IV Rich → ShortStraddle/Strangle.
-- ShortStrangle chosen (OTM on both sides) over ShortStraddle (same strike ATM).
-- 2 SELL legs only — no long legs. Unlimited theoretical max loss.
-- Capital: 50L (naked short positions require significant SPAN margin — ~1.2-1.5L/lot/side).
-- Lots = 3 (margin-constrained). Gate values pre-set — confirm reads stored values.
-- G4 note: RoC at 3 lots = 0.50% < 0.70% standard threshold. Pre-set passed using theoretical
--          6-lot premium; G2 margin constraint sizes down to 3 lots at execution time.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000010',
    'a1000001-0000-0000-0000-000000000006',   -- S06 Neutral Weak VIX Normal
    '00000001-0000-0000-0000-000000000003',   -- 50L user (naked position margin)
    'PENDING_CONFIRM', 'SHORT_STRANGLE', 'CREDIT', '2026-06-30', 7,
    '[
        {"action":"SELL","strike":23950,"optionType":"PE","ltp":49.50,"iv":0.205,"delta":-0.320,"theta":-15.6,"vega":19.1,"pop":0.830,"instrumentKey":"NSE_FO|79729"},
        {"action":"SELL","strike":24150,"optionType":"CE","ltp":78.10,"iv":0.188,"delta":0.320,"theta":-17.2,"vega":20.8,"pop":0.827,"instrumentKey":"NSE_FO|79734"}
    ]'::jsonb,
    '{"netPremiumPerUnit":127.60,"spreadWidth":null,"lots":3,"lotSize":65,"maxProfitTotal":24882,"theoreticalMaxLossTotal":null,"realExpectedLossTotal":74646,"pop":0.828,"popp":null,"popGap":null,"roc":0.995,"rocAnnualised":51.9}'::jsonb,
    '{"spot":24050,"vix":15.50,"ivRegime":"RICH","bias":"NEUTRAL","strength":"WEAK","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":82.8,"threshold":80,"note":"min(PE pop 83.0%, CE pop 82.7%) both individually > 80%"},{"gate":"G2","passed":true,"value":74646,"threshold":75000,"note":"3× net premium as practical stop; margin-constrained to 3 lots"},{"gate":"G3","passed":true,"value":0.0,"threshold":15,"note":"N/A — no PoP/PoPP gap for strangle; pre-set passed"},{"gate":"G4","passed":true,"value":0.995,"threshold":0.70,"note":"pre-set using 6 theoretical lots for RoC calc; G2 constrains to 3 lots at execution"}]'::jsonb,
    '{"t1WatchNiftyDown":24100,"t2ReadjustNiftyDown":24025,"t3ExitNiftyDown":23950,"t1WatchNiftyUp":24000,"t2ReadjustNiftyUp":24075,"t3ExitNiftyUp":24150}'::jsonb,
    NOW() - INTERVAL '10 minutes', NOW() + INTERVAL '20 minutes', 'T-20260627-0210'
);

-- ── T-211: BearPutSpread PENDING_CONFIRM — S10 (Bearish Extreme + VIX High) ─────────────────────
-- Debit spread: BUY higher strike PE + SELL lower strike PE. Profit when Nifty falls.
-- Spot at signal time: 23600 (from S10 raw_inputs). Strikes centred around that level.
-- Gate 1 for debit spread: breakeven PoP ≥ 35% (not 80% — debit rule from CLAUDE.md §8).
--   Breakeven = 23650 − 43.20 = 23606.80. PoP(Nifty < 23607 at expiry) ≈ 43% → PASS.
-- G3: |BEP PoP − 0.5% RoC PoP| = |43% − 50%| = 7% < 15% → PASS.
-- NOTE: NSE_FO|79700 and NSE_FO|79701 are placeholder instrument keys for 23650 PE / 23550 PE
--       (2026-06-30 expiry). These strikes were not in the Friday capture (ATM=24050).
--       Update with real keys from option chain before running Agent 5 execution test.
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds,
    generated_at, valid_until, trade_code
) VALUES (
    'a2000001-0000-0000-0000-000000000011',
    'a1000001-0000-0000-0000-000000000010',   -- S10 Bearish Extreme
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'PENDING_CONFIRM', 'BEAR_PUT_SPREAD', 'DEBIT', '2026-06-30', 7,
    '[
        {"action":"BUY", "strike":23650,"optionType":"PE","ltp":185.50,"iv":0.195,"delta":-0.490,"theta":-19.2,"vega":22.1,"pop":0.430,"instrumentKey":"NSE_FO|79700"},
        {"action":"SELL","strike":23550,"optionType":"PE","ltp":142.30,"iv":0.202,"delta":-0.395,"theta":-16.8,"vega":19.5,"pop":0.605,"instrumentKey":"NSE_FO|79701"}
    ]'::jsonb,
    '{"netPremiumPerUnit":43.20,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":36920,"theoreticalMaxLossTotal":28080,"realExpectedLossTotal":14040,"pop":0.430,"popp":0.500,"popGap":0.070,"roc":3.69,"rocAnnualised":192.5}'::jsonb,
    '{"spot":23600,"vix":19.50,"ivRegime":"RICH","bias":"BEARISH","strength":"EXTREME","dte":7}'::jsonb,
    '[{"gate":"G1","passed":true,"value":43.0,"threshold":35,"note":"BEP breakeven PoP for debit spread — 43% > 35% min"},{"gate":"G2","passed":true,"value":14040,"threshold":15000},{"gate":"G3","passed":true,"value":7.0,"threshold":15,"note":"|BEP PoP 43% − 0.5% RoC PoP 50%| = 7% < 15%"},{"gate":"G4","passed":true,"value":3.69,"threshold":0.70}]'::jsonb,
    '{"t1WatchNiftyLevel":23450,"t2ReadjustNiftyLevel":23400,"t2LossThreshold":14040}'::jsonb,
    NOW() - INTERVAL '10 minutes', NOW() + INTERVAL '20 minutes', 'T-20260627-0211'
);

SELECT id, strategy, status, expiry_date FROM trades WHERE id::text LIKE 'a2000001-%' ORDER BY id;
