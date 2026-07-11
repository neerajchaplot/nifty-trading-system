#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# S3 — Agent 3 silo tests
#
# All tests use the niftySpot override body — works OFFLINE (no Upstox needed).
# Pre-requisites: Run 04_seed_agent3_active_trades.sql first.
#
# CreditSpreadMonitorStrategy uses PoP-based decisions (not Nifty level thresholds):
#   PoP ≥ 80% → HOLD    PoP 75–79% → WATCH
#   PoP 65–74% → READJUST    PoP < 65% → EXIT
#   VIX > 24 → PAUSE   Spot ≤ short strike (PE) → T3_SHORT_STRIKE_BREACH → EXIT
#
# IV overrides calibrated for DTE=4 (test date 2026-07-03, expiry 2026-07-07):
#   HOLD     : spot=24450, σ=0.185 → PoP≈83.8%
#   WATCH    : spot=24350, σ=0.192 → PoP≈77.2%
#   READJUST : spot=24200, σ=0.200 → PoP≈66.3%
#   EXIT     : spot=23950 → breach detection (σ irrelevant)
#
# BullCallSpread (T-306, T-307): DebitSpreadMonitorStrategy
#   shortLeg=SELL CE 24250, longLeg=BUY CE 24100, entryNetDebit=58.90
#   t1WatchNiftyLevel=24200 (→ WATCH), t2ReadjustNiftyLevel=24250 (→ EXIT profit)
#   t2LossThreshold=3829 (total Rs loss stop = 50% of debit paid)
# ─────────────────────────────────────────────────────────────────────────────
source "$(dirname "$0")/vars.sh"

h "S3 — Agent 3 silo tests (offline mode)"

# ────────────────────────────────────────────────────────────────────────────
# S3.1 — HOLD: spot=24450, σ=0.185 → PoP≈83.8% ≥ 80% → HOLD
# Trade: T-301 BullPutSpread (short=24000)
# ────────────────────────────────────────────────────────────────────────────

h "S3.1 — Expected action: HOLD (spot 24450, σ=0.185, PoP≈83.8%)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_HOLD" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 24450,
    "vix": 17.5,
    "shortLegLtp": 15.00,
    "longLegLtp":  6.00,
    "shortLegIv":  0.185
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.2 — WATCH: spot=24350, σ=0.192 → PoP≈77.2% (75–79%) → WATCH
# Trade: T-302 BullPutSpread (short=24000)
# ────────────────────────────────────────────────────────────────────────────

h "S3.2 — Expected action: WATCH (spot 24350, σ=0.192, PoP≈77.2%)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_WATCH" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 24350,
    "vix": 18.2,
    "shortLegLtp": 28.00,
    "longLegLtp":  14.00,
    "shortLegIv":  0.192
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.3 — READJUST: spot=24200, σ=0.200 → PoP≈66.3% (65–74%) → READJUST
# Trade: T-303 BullPutSpread (short=24000)
# ────────────────────────────────────────────────────────────────────────────

h "S3.3 — Expected action: READJUST (spot 24200, σ=0.200, PoP≈66.3%)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_READJUST" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 24200,
    "vix": 19.1,
    "shortLegLtp": 55.00,
    "longLegLtp":  35.00,
    "shortLegIv":  0.200
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.4 — EXIT: Nifty breaches short strike (23950 < 24000)
# ────────────────────────────────────────────────────────────────────────────

h "S3.4 — Expected action: EXIT (spot 23950, breached short strike 24000)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_EXIT" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 23950,
    "vix": 19.8,
    "shortLegLtp": 148.00,
    "longLegLtp":  105.00,
    "shortLegIv":  0.238
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.5 — PAUSE: VIX Extreme override
# Spot=24450 (fine, PoP would be HOLD) but VIX=32.0 > 24 (Extreme)
# Expected: PAUSE — VIX Extreme check fires before any PoP/price logic.
# MonitorAction.PAUSE = auto-trading suspended, manual review required.
# ────────────────────────────────────────────────────────────────────────────

h "S3.5 — Expected action: PAUSE (VIX=32.0 Extreme, spot 24450 is fine but VIX overrides)"
info "VIX > 24 triggers PAUSE (auto-trading suspended, manual review required)."
info "VIX Extreme check fires FIRST before any PoP/price logic."
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_VIX" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 24450,
    "vix": 32.0,
    "shortLegLtp": 15.00,
    "longLegLtp":  6.00,
    "shortLegIv":  0.185
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.6 — EXIT (profit): BullCallSpread — Nifty reaches T2 1% RoC target
# Trade: T-306 BullCallSpread (BUY CE 24100 / SELL CE 24250)
# t2ReadjustNiftyLevel=24250. Send spot=24250 → T2 profit target hit → EXIT.
# shortLegLtp=55.00 (SELL CE 24250), longLegLtp=160.00 (BUY CE 24100)
# MTM = (160-55 - 58.90) × 130 = +5993 (profit, no loss stop triggered)
# ────────────────────────────────────────────────────────────────────────────

h "S3.6 — Expected action: EXIT profit (BullCallSpread, spot 24250 > T1=24200)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_PROFIT" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 24250,
    "vix": 14.8,
    "shortLegLtp": 55.00,
    "longLegLtp":  160.00,
    "shortLegIv":  0.162
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.7 — EXIT (loss cut): BullCallSpread — MTM loss > 50% of premium paid
# Trade: T-307. entryNetDebit=58.90. t2LossThreshold=3829.
# spot=24000: both CE 24100 (longLeg) and CE 24250 (shortLeg) deeply OTM.
# shortLegLtp=2.00 (SELL CE 24250), longLegLtp=10.00 (BUY CE 24100)
# currentNetPremium(DEBIT) = 10 - 2 = 8.00
# MTM = (8.00 - 58.90) × 2 × 65 = -50.90 × 130 = -6617 ≤ -3829 → EXIT
# ────────────────────────────────────────────────────────────────────────────

h "S3.7 — Expected action: EXIT loss cut (BullCallSpread, MTM loss Rs 6617 > threshold Rs 3829)"
info "spot=24000 → both CE 24100 and CE 24250 deeply OTM with DTE=4."
info "shortLegLtp=2.00 (SELL CE 24250), longLegLtp=10.00 (BUY CE 24100)"
info "currentNetPremium = 10-2 = 8. MTM = (8 - 58.90) × 2 × 65 = -6617 ≤ -3829 → EXIT"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_LOSSCUT" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 24000,
    "vix": 16.2,
    "shortLegLtp": 2.00,
    "longLegLtp":  10.00,
    "shortLegIv":  0.175
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.8 — Error path: evaluate CLOSED trade
# Status must be ACTIVE or EXIT_FAILED — CLOSED should return 409
# ────────────────────────────────────────────────────────────────────────────

h "S3.8 — Error: evaluate CLOSED trade (expect 409 Conflict)"
info "Set a trade to CLOSED first:"
info "UPDATE zupptrade_dev.trades SET status='CLOSED' WHERE id='$T_A3_HOLD';"
info "(revert after test)"
# TODO: Add pre-seeded CLOSED trade (T-308) so this test runs without manual SQL.
# See test-data/TODO.md
# curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_HOLD" \
#   -H "Content-Type: application/json" \
#   -d '{"niftySpot": 24300, "vix": 17.5}'

h "S3 DONE"
