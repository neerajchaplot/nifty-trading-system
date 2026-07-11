-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: T-401 — Iron Condor ACTIVE, calibrated to trigger READJUST via scheduler
--
-- PURPOSE
--   Tests the full 6-step ReadjustmentService chain via the real scheduler loop
--   (not the /evaluate endpoint silo test). The scheduler fetches live Upstox
--   option chain data and computes MTM P&L — this seed is calibrated so that
--   READJUST fires on the very first scheduler cycle after loading.
--
-- READJUST TRIGGER MECHANISM
--   IronCondorMonitorStrategy checks P&L before proximity zones (steps 3/4 before 5/6).
--
--   P&L formula (CREDIT spread):
--     mtmPnl = (actualNetPremiumPerUnit - currentNetPremium) × lots × lotSize
--     currentNetPremium = liveShortLegLtp − liveLongLegLtp   (PE spread from Upstox chain)
--
--   With actualNetPremiumPerUnit = 1.00 (seeded as nearly-zero entry premium):
--     PE 24000 live LTP ≈ 60–100 Rs, PE 23900 live LTP ≈ 30–50 Rs
--     currentNetPremium ≈ 30–60 Rs  >>>  1.00
--     mtmPnl ≈ −9750 to −19175 (large loss)
--
--   t2LossThreshold = 1  →  any loss ≥ Rs.1 fires T2_READJUST_PNL ✓
--   t3LossThreshold = 9999999  →  P&L can NEVER fire EXIT — READJUST guaranteed ✓
--
--   Proximity thresholds set to extreme values (20000/19900 down, 28000/28100 up)
--   so they do NOT fire before the P&L check.
--
-- SHORT STRIKE BREACH GUARD
--   PE short 24000: breach fires if spot ≤ 24000  (needs Nifty to fall 50+ pts from ~24050)
--   CE short 24150: breach fires if spot ≥ 24150  (needs Nifty to rise 100+ pts from ~24050)
--   Run during market hours on a stable day — breach is unlikely in a 5-min window.
--   If Nifty does breach, EXIT fires instead of READJUST — that is a different valid test.
--
-- PREREQUISITES
--   - All agents running with sandbox profile (simulate-fills=true, simulate-exit=true)
--   - Run during IST market hours 09:15–15:30 (Mon–Fri) — scheduler only fires then
--   - Scheduler cron: "0 */5 9-15 * * MON-FRI" — next tick within 5 minutes
--   - Valid Upstox access token (option chain fetch required for live LTPs)
--   - Run AFTER 01–04 seeds so user_profile_id FK is satisfied
--
-- INSTRUMENTS (Friday 2026-07-03 capture, expiry 2026-07-07, ATM: update from capture)
--   NSE_FO|44621 = PE 24000  NSE_FO|44617 = PE 23900
--   NSE_FO|44635 = CE 24150  NSE_FO|44642 = CE 24250
--
-- CLEANUP
--   Covered by 99_cleanup.sql (agent1_signal_id LIKE 'a1000001-%' cascade).
--   Or run the DELETE block at the bottom of this file directly.
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

-- Idempotent: clean up any prior run of this seed
DELETE FROM monitoring_evaluations WHERE trade_id = 'a3000001-0000-0000-0000-000000000010';
DELETE FROM trade_ledger            WHERE trade_id = 'a3000001-0000-0000-0000-000000000010';
DELETE FROM trades                  WHERE id       = 'a3000001-0000-0000-0000-000000000010';

-- ─────────────────────────────────────────────────────────────────────────────
-- T-401: Iron Condor ACTIVE — P&L calibrated READJUST trigger
--
--   PE spread : SELL PE 24000 (NSE_FO|44621) / BUY PE 23900 (NSE_FO|44617)
--   CE spread : SELL CE 24150 (NSE_FO|44635) / BUY CE 24250 (NSE_FO|44642)
--   lots=5  lotSize=65  qty=325/leg
--
--   actualNetPremiumPerUnit = 1.00  ← CALIBRATION KEY
--   t2LossThreshold = 1             ← any loss triggers READJUST
--   t3LossThreshold = 9999999       ← P&L can never trigger EXIT
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO trades (
    id, user_profile_id,
    status, strategy, expiry_date, dte,
    legs, summary, market_context, gate_results, thresholds, monitor_config, entry_fills,
    generated_at, valid_until, confirmed_at, trade_code
) VALUES (
    'a3000001-0000-0000-0000-000000000010',
    '00000001-0000-0000-0000-000000000002',   -- 10L user
    'ACTIVE', 'IRON_CONDOR', '2026-07-07', 4,

    -- legs: IC 4-leg definition
    '[
        {"action":"SELL","strike":24000,"optionType":"PE","instrumentKey":"NSE_FO|44621"},
        {"action":"BUY", "strike":23900,"optionType":"PE","instrumentKey":"NSE_FO|44617"},
        {"action":"SELL","strike":24150,"optionType":"CE","instrumentKey":"NSE_FO|44635"},
        {"action":"BUY", "strike":24250,"optionType":"CE","instrumentKey":"NSE_FO|44642"}
    ]'::jsonb,

    -- summary: actualNetPremiumPerUnit=1.00 is the calibration key
    -- maxProfitTotal = 1.00 × 5 × 65 = 325
    -- actualMaxLossTotal = (100−1) × 5 × 65 = 32175 (PE spread width 100 pts − net 1)
    '{"netPremiumPerUnit":1.00,"spreadWidth":100,"lots":5,"lotSize":65,
      "maxProfitTotal":325,"theoreticalMaxLossTotal":32175,"realExpectedLossTotal":16088}'::jsonb,

    -- market_context: entry snapshot (neutral market, reasonable VIX)
    '{"spot":24050,"vix":20.5,"ivRegime":"RICH","bias":"NEUTRAL","strength":"WEAK","dte":4}'::jsonb,

    -- gate_results: all passed (trade was valid at entry)
    '[{"gate":"G1","passed":true},{"gate":"G2","passed":true},{"gate":"G3","passed":true},{"gate":"G4","passed":true}]'::jsonb,

    -- thresholds: stored on trade row for audit / UI display
    -- IC bilateral format — 2-leg directional fields (t1/t2/t3 NiftyLevel) are absent
    '{"t2LossThreshold":1,"t3LossThreshold":9999999,
      "t1WatchNiftyDown":20000,"t2ReadjustNiftyDown":19900,"t3ExitNiftyDown":19000,
      "t1WatchNiftyUp":28000,"t2ReadjustNiftyUp":28100,"t3ExitNiftyUp":29000}'::jsonb,

    -- monitor_config: what Agent 3 scheduler reads — this is the critical JSONB
    -- actualNetPremiumPerUnit=1.00 guarantees immediate P&L loss vs any live LTPs
    -- t2LossThreshold=1 fires READJUST on the very first P&L check
    -- t3LossThreshold=9999999 ensures P&L never fires EXIT (only proximity/breach can)
    -- proximity thresholds set to extreme values — will NOT fire before P&L check
    '{
        "tradeId":   "a3000001-0000-0000-0000-000000000010",
        "strategy":  "IRON_CONDOR",
        "spreadDirection": "CREDIT",
        "shortLeg":  {"strike":24000,"optionType":"PE","action":"SELL","ltp":64.50,"instrumentKey":"NSE_FO|44621"},
        "longLeg":   {"strike":23900,"optionType":"PE","action":"BUY", "ltp":38.15,"instrumentKey":"NSE_FO|44617"},
        "shortLeg2": {"strike":24150,"optionType":"CE","action":"SELL","ltp":78.10,"instrumentKey":"NSE_FO|44635"},
        "longLeg2":  {"strike":24250,"optionType":"CE","action":"BUY", "ltp":43.55,"instrumentKey":"NSE_FO|44642"},
        "actualNetPremiumPerUnit": 1.00,
        "lots": 5,
        "lotSize": 65,
        "maxProfitTotal": 325,
        "actualMaxLossTotal": 32175,
        "slippageAlert": false,
        "slippageAmount": 0,
        "thresholds": {
            "t2LossThreshold": 1,
            "t3LossThreshold": 9999999,
            "t1WatchNiftyDown": 20000,
            "t2ReadjustNiftyDown": 19900,
            "t3ExitNiftyDown": 19000,
            "t1WatchNiftyUp": 28000,
            "t2ReadjustNiftyUp": 28100,
            "t3ExitNiftyUp": 29000
        },
        "expiryDate": "2026-07-07",
        "dte": 4
    }'::jsonb,

    -- entry_fills: simulated fills matching actualNetPremiumPerUnit = 1.00
    -- (PE sold at 64.50, bought at 38.15 → net 26.35; CE sold at 78.10, bought at 43.55 → net 34.55)
    -- Combined IC net premium per unit in reality ≈ 60.90, but monitor_config overrides to 1.00
    -- This discrepancy is intentional — it creates an immediate MTM loss on first scheduler cycle.
    '[
        {"orderId":"SIM-A3000010-L0","instrumentKey":"NSE_FO|44621","action":"SELL","strike":24000,"optionType":"PE","quantityFilled":325,"averageFillPrice":64.50},
        {"orderId":"SIM-A3000010-L1","instrumentKey":"NSE_FO|44617","action":"BUY", "strike":23900,"optionType":"PE","quantityFilled":325,"averageFillPrice":38.15},
        {"orderId":"SIM-A3000010-L2","instrumentKey":"NSE_FO|44635","action":"SELL","strike":24150,"optionType":"CE","quantityFilled":325,"averageFillPrice":78.10},
        {"orderId":"SIM-A3000010-L3","instrumentKey":"NSE_FO|44642","action":"BUY", "strike":24250,"optionType":"CE","quantityFilled":325,"averageFillPrice":43.55}
    ]'::jsonb,

    NOW() - INTERVAL '3 hours',             -- generated_at
    NOW() - INTERVAL '2 hours 50 minutes',  -- valid_until (in the past — trade is already live)
    NOW() - INTERVAL '2 hours',             -- confirmed_at
    'T-20260703-0401'                        -- trade_code
);

-- Verify seed inserted correctly
SELECT id, strategy, status, expiry_date, trade_code,
       (monitor_config ->> 'actualNetPremiumPerUnit')::numeric AS seeded_net_premium,
       (monitor_config -> 'thresholds' ->> 't2LossThreshold')::numeric  AS t2_loss_threshold,
       (monitor_config -> 'thresholds' ->> 't3LossThreshold')::numeric  AS t3_loss_threshold
FROM trades
WHERE id = 'a3000001-0000-0000-0000-000000000010';
