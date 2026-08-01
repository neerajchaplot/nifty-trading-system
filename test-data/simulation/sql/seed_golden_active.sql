-- ─────────────────────────────────────────────────────────────────────────────
-- Load the two v1 golden fixtures as ACTIVE trades (Phase-B seedTrade).
--
-- PROVISIONAL (contract §5): these mirror the golden JSON files
--   fixtures/golden/bull_put_10L.json  and  iron_condor_50L.json
-- and must be regenerated from a real Phase-A run before being trusted.
--
-- Keep the monitor_config here in sync with those JSON files by hand for now;
-- the Increment-1 loader will read the JSON directly.
--
-- Prereq: 01_seed_user_profiles.sql (10L=...0002, 50L=...0003) and
--         02_seed_agent1_signals.sql (S02=...0002, S05=...0005) already loaded —
--         contract §2.1 requires a real agent1_signal_id for Phase C.
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

DELETE FROM trades WHERE id::text LIKE 'b0000001-%';

-- ── Golden #1: BULL_PUT_SPREAD (10L) ─────────────────────────────────────────
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'b0000001-0000-0000-0000-000000000001',
    'a1000001-0000-0000-0000-000000000002',   -- S02
    '00000001-0000-0000-0000-000000000002',   -- 10L
    'ACTIVE', 'BULL_PUT_SPREAD', 'CREDIT', '2026-07-28', 4,
    '[{"action":"SELL","strike":24000,"optionType":"PE","instrumentKey":"NSE_FO|44621"},
      {"action":"BUY", "strike":23900,"optionType":"PE","instrumentKey":"NSE_FO|44617"}]'::jsonb,
    '{"netPremiumPerUnit":26.35,"spreadWidth":100,"lots":10,"lotSize":65,"maxProfitTotal":17128,"theoreticalMaxLossTotal":47873,"realExpectedLossTotal":23937}'::jsonb,
    '{"spot":24350,"vix":20.50,"ivRegime":"RICH","bias":"BULLISH","strength":"MILD","dte":4}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t3ExitNiftyLevel":24000,"t2LossThreshold":11968,"t3LossThreshold":23937}'::jsonb,
    '{
        "tradeId":"b0000001-0000-0000-0000-000000000001",
        "strategy":"BULL_PUT_SPREAD","spreadDirection":"CREDIT",
        "shortLeg":{"strike":24000,"optionType":"PE","action":"SELL","ltp":64.50,"instrumentKey":"NSE_FO|44621"},
        "longLeg": {"strike":23900,"optionType":"PE","action":"BUY", "ltp":38.15,"instrumentKey":"NSE_FO|44617"},
        "actualNetPremiumPerUnit":26.35,"lots":10,"lotSize":65,
        "maxProfitTotal":17128,"actualMaxLossTotal":47873,"slippageAlert":false,"slippageAmount":0,
        "thresholds":{"t1WatchNiftyLevel":24150,"t2ReadjustNiftyLevel":24075,"t3ExitNiftyLevel":24000,"t2LossThreshold":11968,"t3LossThreshold":23937},
        "expiryDate":"2026-07-28","dte":4
    }'::jsonb,
    '[{"orderId":"SIM-BPS-L0","instrumentKey":"NSE_FO|44621","action":"SELL","strike":24000,"optionType":"PE","quantityFilled":650,"averageFillPrice":64.50},
      {"orderId":"SIM-BPS-L1","instrumentKey":"NSE_FO|44617","action":"BUY","strike":23900,"optionType":"PE","quantityFilled":650,"averageFillPrice":38.15}]'::jsonb,
    NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours 50 minutes', NOW() - INTERVAL '2 hours', 'SIM-GOLDEN-BPS'
);

-- ── Golden #2: IRON_CONDOR (50L) ─────────────────────────────────────────────
INSERT INTO trades (
    id, agent1_signal_id, user_profile_id,
    status, strategy, spread_direction, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'b0000001-0000-0000-0000-000000000002',
    'a1000001-0000-0000-0000-000000000005',   -- S05
    '00000001-0000-0000-0000-000000000003',   -- 50L
    'ACTIVE', 'IRON_CONDOR', 'CREDIT', '2026-07-28', 4,
    '[{"action":"SELL","strike":23900,"optionType":"PE","instrumentKey":"NSE_FO|44617"},
      {"action":"BUY", "strike":23850,"optionType":"PE","instrumentKey":"NSE_FO|44615"},
      {"action":"SELL","strike":24150,"optionType":"CE","instrumentKey":"NSE_FO|44635"},
      {"action":"BUY", "strike":24250,"optionType":"CE","instrumentKey":"NSE_FO|44642"}]'::jsonb,
    '{"netPremiumPerUnit":43.25,"spreadWidth":100,"lots":40,"lotSize":65,"maxProfitTotal":112450,"theoreticalMaxLossTotal":147550,"realExpectedLossTotal":73775}'::jsonb,
    '{"spot":24050,"vix":21.00,"ivRegime":"RICH","bias":"NEUTRAL","strength":"WEAK","dte":4}'::jsonb,
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,
    '{"t1WatchNiftyDown":24050,"t2ReadjustNiftyDown":23975,"t3ExitNiftyDown":23900,"t1WatchNiftyUp":24000,"t2ReadjustNiftyUp":24075,"t3ExitNiftyUp":24150,"t2LossThreshold":36887,"t3LossThreshold":73775}'::jsonb,
    '{
        "tradeId":"b0000001-0000-0000-0000-000000000002",
        "strategy":"IRON_CONDOR","spreadDirection":"CREDIT",
        "shortLeg": {"strike":23900,"optionType":"PE","action":"SELL","ltp":38.15,"instrumentKey":"NSE_FO|44617"},
        "longLeg":  {"strike":23850,"optionType":"PE","action":"BUY", "ltp":29.45,"instrumentKey":"NSE_FO|44615"},
        "shortLeg2":{"strike":24150,"optionType":"CE","action":"SELL","ltp":78.10,"instrumentKey":"NSE_FO|44635"},
        "longLeg2": {"strike":24250,"optionType":"CE","action":"BUY", "ltp":43.55,"instrumentKey":"NSE_FO|44642"},
        "actualNetPremiumPerUnit":43.25,"lots":40,"lotSize":65,
        "maxProfitTotal":112450,"actualMaxLossTotal":147550,"slippageAlert":false,"slippageAmount":0,
        "thresholds":{"t1WatchNiftyDown":24050,"t2ReadjustNiftyDown":23975,"t3ExitNiftyDown":23900,"t1WatchNiftyUp":24000,"t2ReadjustNiftyUp":24075,"t3ExitNiftyUp":24150,"t2LossThreshold":36887,"t3LossThreshold":73775},
        "expiryDate":"2026-07-28","dte":4
    }'::jsonb,
    '[{"orderId":"SIM-IC-L0","instrumentKey":"NSE_FO|44617","action":"SELL","strike":23900,"optionType":"PE","quantityFilled":2600,"averageFillPrice":38.15},
      {"orderId":"SIM-IC-L1","instrumentKey":"NSE_FO|44615","action":"BUY","strike":23850,"optionType":"PE","quantityFilled":2600,"averageFillPrice":29.45},
      {"orderId":"SIM-IC-L2","instrumentKey":"NSE_FO|44635","action":"SELL","strike":24150,"optionType":"CE","quantityFilled":2600,"averageFillPrice":78.10},
      {"orderId":"SIM-IC-L3","instrumentKey":"NSE_FO|44642","action":"BUY","strike":24250,"optionType":"CE","quantityFilled":2600,"averageFillPrice":43.55}]'::jsonb,
    NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours 50 minutes', NOW() - INTERVAL '2 hours', 'SIM-GOLDEN-IC'
);

SELECT id, strategy, status, trade_code FROM trades WHERE id::text LIKE 'b0000001-%' ORDER BY id;
