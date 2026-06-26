-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: agent1_signals — 12 scenarios covering all bias/strength/VIX combos
-- Expiry: 2026-07-01 (next Tuesday after weekend — update if needed)
-- Fixed UUIDs so curl scripts can reference them predictably
-- ─────────────────────────────────────────────────────────────────────────────
SET search_path TO zupptrade_dev;

DELETE FROM agent1_signals WHERE id::text LIKE 'a1000001-%';

INSERT INTO agent1_signals (
    id, timestamp, expiry_date,
    bias, strength, composite_score,
    confidence, confidence_label,
    vix_level, vix_regime, vix_direction,
    score_breakdown, data_gaps, commentary_divergence,
    key_levels, raw_inputs, status
) VALUES

-- ── S01: Bullish Extreme ──────────────────────────────────────────────────────
-- All 5 tiers bullish. VIX Normal. → Agent 2 should select BullCallSpread (debit)
(
    'a1000001-0000-0000-0000-000000000001',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'BULLISH', 'EXTREME', 0.6200,
    0.82, 'HIGH',
    15.20, 'NORMAL', 'FALLING',
    '{"tier1a": 0.90, "tier1b": 0.80, "tier2": 0.75, "tier3": 0.70, "tier4": 0.60}'::jsonb,
    '[]'::jsonb, false,
    '{"support": 24000, "resistance": 24800}'::jsonb,
    '{"spot": 24350, "ema20": 24100, "ema50": 23800, "ema200": 23200, "pcr": 1.35, "fii_net": 820, "dii_net": 650, "gift_nifty_premium": 75}'::jsonb,
    'ACTIVE'
),

-- ── S02: Bullish Mild + VIX High + IV Rich ────────────────────────────────────
-- Most tiers bullish. VIX High. → Agent 2 should select BullPutSpread (credit, IV rich)
(
    'a1000001-0000-0000-0000-000000000002',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'BULLISH', 'MILD', 0.3800,
    0.58, 'MEDIUM',
    20.50, 'HIGH', 'STABLE',
    '{"tier1a": 0.60, "tier1b": 0.40, "tier2": 0.50, "tier3": 0.20, "tier4": 0.30}'::jsonb,
    '[]'::jsonb, false,
    '{"support": 23800, "resistance": 24500}'::jsonb,
    '{"spot": 24120, "ema20": 24000, "ema50": 23750, "ema200": 23100, "pcr": 1.10, "fii_net": 420, "dii_net": 380, "gift_nifty_premium": 40}'::jsonb,
    'ACTIVE'
),

-- ── S03: Bullish Mild + VIX Normal + IV Fair ─────────────────────────────────
-- Consistent mild bullish. VIX Normal. → Agent 2 should select BullPutSpread
(
    'a1000001-0000-0000-0000-000000000003',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'BULLISH', 'MILD', 0.3400,
    0.54, 'MEDIUM',
    15.80, 'NORMAL', 'STABLE',
    '{"tier1a": 0.57, "tier1b": 0.40, "tier2": 0.43, "tier3": 0.20, "tier4": 0.30}'::jsonb,
    '[]'::jsonb, false,
    '{"support": 23900, "resistance": 24600}'::jsonb,
    '{"spot": 24200, "ema20": 24050, "ema50": 23820, "ema200": 23150, "pcr": 1.05, "fii_net": 310, "dii_net": 450, "gift_nifty_premium": 55}'::jsonb,
    'ACTIVE'
),

-- ── S04: Bullish Weak (Agent 2 treats as Neutral) ────────────────────────────
-- Score in 0.10–0.25 range. Agent 2 redirects to Neutral strategy path.
(
    'a1000001-0000-0000-0000-000000000004',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'BULLISH', 'WEAK', 0.1800,
    0.30, 'LOW',
    14.00, 'NORMAL', 'STABLE',
    '{"tier1a": 0.29, "tier1b": 0.20, "tier2": 0.24, "tier3": 0.10, "tier4": 0.0}'::jsonb,
    '["fii_options_flow"]'::jsonb, false,
    NULL,
    '{"spot": 24080, "ema20": 23950, "ema50": 23700, "ema200": 23000, "pcr": 0.95, "fii_net": 180}'::jsonb,
    'ACTIVE'
),

-- ── S05: Neutral Weak + VIX High + IV Rich ───────────────────────────────────
-- Tiers split evenly. High IV/HV ratio. → Agent 2 should select IronCondor
(
    'a1000001-0000-0000-0000-000000000005',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'NEUTRAL', 'WEAK', 0.0500,
    0.28, 'LOW',
    21.00, 'HIGH', 'RISING',
    '{"tier1a": 0.14, "tier1b": 0.0, "tier2": 0.10, "tier3": -0.10, "tier4": -0.10}'::jsonb,
    '[]'::jsonb, false,
    '{"support": 23600, "resistance": 24400}'::jsonb,
    '{"spot": 24000, "ema20": 23980, "ema50": 23850, "ema200": 23100, "pcr": 1.00, "fii_net": -120, "dii_net": 200}'::jsonb,
    'ACTIVE'
),

-- ── S06: Neutral Weak + VIX Normal + IV Rich ─────────────────────────────────
-- Sideways market, IV elevated. → Agent 2 should select ShortStraddle/Strangle
(
    'a1000001-0000-0000-0000-000000000006',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'NEUTRAL', 'WEAK', 0.0300,
    0.48, 'MEDIUM',
    15.50, 'NORMAL', 'STABLE',
    '{"tier1a": 0.0, "tier1b": 0.10, "tier2": 0.0, "tier3": 0.0, "tier4": 0.10}'::jsonb,
    '[]'::jsonb, false,
    '{"support": 23700, "resistance": 24300}'::jsonb,
    '{"spot": 24000, "ema20": 24010, "ema50": 23990, "ema200": 23200, "pcr": 1.02, "fii_net": 50, "dii_net": 80}'::jsonb,
    'ACTIVE'
),

-- ── S07: Neutral + VIX Low + IV Cheap → SKIP ─────────────────────────────────
-- VIX below 13. IV/HV ratio < 0.85 (cheap). → Agent 2 SKIP — no trade
(
    'a1000001-0000-0000-0000-000000000007',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'NEUTRAL', 'WEAK', 0.0400,
    0.50, 'MEDIUM',
    11.00, 'LOW', 'FALLING',
    '{"tier1a": 0.14, "tier1b": 0.0, "tier2": 0.0, "tier3": 0.0, "tier4": 0.0}'::jsonb,
    '["fii_options_flow", "gift_nifty"]'::jsonb, false,
    NULL,
    '{"spot": 24200, "ema20": 24180, "ema50": 24050, "ema200": 23300, "pcr": 1.05, "fii_net": 80}'::jsonb,
    'ACTIVE'
),

-- ── S08: VIX Extreme → SKIP, alert user ──────────────────────────────────────
-- VIX > 24. → Agent 2 must SKIP regardless of bias. Alert user.
(
    'a1000001-0000-0000-0000-000000000008',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'NEUTRAL', 'WEAK', 0.0200,
    0.18, 'LOW',
    27.50, 'EXTREME', 'RISING',
    '{"tier1a": -0.14, "tier1b": -0.20, "tier2": 0.10, "tier3": -0.50, "tier4": -0.30}'::jsonb,
    '[]'::jsonb, false,
    '{"support": 22500, "resistance": 24000}'::jsonb,
    '{"spot": 23200, "ema20": 23800, "ema50": 24000, "ema200": 23500, "pcr": 0.72, "fii_net": -1200, "dii_net": 950}'::jsonb,
    'ACTIVE'
),

-- ── S09: Bearish Mild + VIX High ─────────────────────────────────────────────
-- Most tiers bearish. VIX High. → Agent 2 should select BearCallSpread (credit)
(
    'a1000001-0000-0000-0000-000000000009',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'BEARISH', 'MILD', -0.3500,
    0.55, 'MEDIUM',
    20.80, 'HIGH', 'RISING',
    '{"tier1a": -0.57, "tier1b": -0.40, "tier2": -0.50, "tier3": -0.30, "tier4": -0.20}'::jsonb,
    '[]'::jsonb, false,
    '{"support": 23000, "resistance": 24000}'::jsonb,
    '{"spot": 23800, "ema20": 24100, "ema50": 24300, "ema200": 23500, "pcr": 0.75, "fii_net": -680, "dii_net": 300, "gift_nifty_premium": -65}'::jsonb,
    'ACTIVE'
),

-- ── S10: Bearish Extreme ──────────────────────────────────────────────────────
-- All tiers bearish. VIX High. → Agent 2 should select BearPutSpread (debit)
(
    'a1000001-0000-0000-0000-000000000010',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'BEARISH', 'EXTREME', -0.5800,
    0.74, 'HIGH',
    19.50, 'HIGH', 'RISING',
    '{"tier1a": -0.86, "tier1b": -0.80, "tier2": -0.75, "tier3": -0.60, "tier4": -0.50}'::jsonb,
    '[]'::jsonb, false,
    '{"support": 22800, "resistance": 23800}'::jsonb,
    '{"spot": 23600, "ema20": 24200, "ema50": 24400, "ema200": 23600, "pcr": 0.68, "fii_net": -1450, "dii_net": 520, "gift_nifty_premium": -85}'::jsonb,
    'ACTIVE'
),

-- ── S11: Bullish Mild with Data Gaps ─────────────────────────────────────────
-- Missing FII/DII data (Tier 2 gaps). Lower confidence due to incomplete inputs.
-- Verifies that missing data scores 0 (neutral) and is logged in data_gaps.
(
    'a1000001-0000-0000-0000-000000000011',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'BULLISH', 'MILD', 0.2800,
    0.35, 'LOW',
    17.00, 'NORMAL', 'STABLE',
    '{"tier1a": 0.57, "tier1b": 0.40, "tier2": 0.0, "tier3": 0.20, "tier4": 0.10}'::jsonb,
    '["fii_futures_net", "fii_long_ratio", "fii_options_net", "dii_net"]'::jsonb, false,
    NULL,
    '{"spot": 24100, "ema20": 23950, "ema50": 23700, "ema200": 23050, "pcr": 1.08}'::jsonb,
    'ACTIVE'
),

-- ── S12: Commentary Divergence ───────────────────────────────────────────────
-- Tier 4 (commentary+news) is BEARISH while tiers 1–3 are BULLISH.
-- Confidence penalty applied (×0.80). commentary_divergence = true.
-- Verifies the confidence penalty logic fires correctly.
(
    'a1000001-0000-0000-0000-000000000012',
    NOW() - INTERVAL '5 minutes', '2026-07-01',
    'BULLISH', 'MILD', 0.2900,
    0.38, 'LOW',
    16.50, 'NORMAL', 'STABLE',
    '{"tier1a": 0.57, "tier1b": 0.40, "tier2": 0.50, "tier3": 0.10, "tier4": -0.40}'::jsonb,
    '[]'::jsonb, true,
    '{"support": 23750, "resistance": 24550}'::jsonb,
    '{"spot": 24050, "ema20": 23900, "ema50": 23650, "ema200": 23000, "pcr": 1.12, "fii_net": 380, "dii_net": 290, "marketaux_sentiment": -0.35}'::jsonb,
    'ACTIVE'
);

-- Verify all 12 inserted
SELECT id, bias, strength, composite_score, vix_regime, confidence_label
FROM agent1_signals
WHERE id::text LIKE 'a1000001-%'
ORDER BY composite_score DESC;
