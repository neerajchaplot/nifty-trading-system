#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# S3 — Agent 3 silo tests
#
# All tests use the niftySpot override body — works OFFLINE (no Upstox needed).
# Pre-requisites: Run 04_seed_agent3_active_trades.sql first.
#
# For shortLegLtp / longLegLtp in WATCH/READJUST scenarios:
#   These are synthetic prices that create a mark-to-market loss at the
#   corresponding threshold. They do not have to be accurate — Agent 3
#   uses them for P&L display but the threshold decision is driven by niftySpot.
# ─────────────────────────────────────────────────────────────────────────────
source "$(dirname "$0")/vars.sh"

h "S3 — Agent 3 silo tests (offline mode)"

# ────────────────────────────────────────────────────────────────────────────
# S3.1 — HOLD: Nifty well above T1
# Trade: T-301 BullPutSpread (short=23500, T1=23650)
# Spot 24200 >> T1 23650 → HOLD
# ────────────────────────────────────────────────────────────────────────────

h "S3.1 — Expected action: HOLD (spot 24200, T1=23650)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_HOLD" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 24200,
    "vix": 18.5,
    "shortLegLtp": 20.50,
    "longLegLtp":  9.80,
    "shortLegIv":  0.185
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.2 — WATCH: Nifty at T1 (short_strike + 150 = 23650)
# ────────────────────────────────────────────────────────────────────────────

h "S3.2 — Expected action: WATCH (spot 23650, exactly at T1)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_WATCH" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 23650,
    "vix": 19.2,
    "shortLegLtp": 58.40,
    "longLegLtp":  28.10,
    "shortLegIv":  0.198
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.3 — READJUST: Nifty at T2 (short_strike + 75 = 23575)
# ────────────────────────────────────────────────────────────────────────────

h "S3.3 — Expected action: READJUST (spot 23575, at T2)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_READJUST" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 23575,
    "vix": 20.5,
    "shortLegLtp": 82.50,
    "longLegLtp":  48.20,
    "shortLegIv":  0.218
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.4 — EXIT: Nifty breaches short strike (23450 < 23500)
# ────────────────────────────────────────────────────────────────────────────

h "S3.4 — Expected action: EXIT (spot 23450, breached short strike 23500)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_EXIT" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 23450,
    "vix": 22.0,
    "shortLegLtp": 115.80,
    "longLegLtp":   72.40,
    "shortLegIv":   0.238
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.5 — EXIT: VIX spike override
# Spot is fine (24100 >> T1=23650) but VIX jumped from 18.5 → 32.0 (+73%)
# ────────────────────────────────────────────────────────────────────────────

h "S3.5 — Expected action: EXIT (VIX spike 18.5→32.0, spot fine)"
info "Previous VIX stored in monitoring_evaluations — seed a row first if needed:"
info "INSERT INTO monitoring_evaluations (trade_id, vix_level, ...) for $T_A3_VIX"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_VIX" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 24100,
    "vix": 32.0,
    "shortLegLtp": 28.50,
    "longLegLtp":  12.30,
    "shortLegIv":  0.220
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.6 — EXIT (profit): BullCallSpread — Nifty above T1 profit target
# Trade: T-306 BullCallSpread (BUY 24200 CE / SELL 24400 CE)
# T1 profit target: Nifty at 24470. Send spot 24480 → EXIT (book profit).
# ────────────────────────────────────────────────────────────────────────────

h "S3.6 — Expected action: EXIT profit (BullCallSpread, spot 24480 > T1=24470)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_PROFIT" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 24480,
    "vix": 14.8,
    "shortLegLtp": 58.20,
    "longLegLtp":  195.40,
    "shortLegIv":  0.162
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.7 — EXIT (loss cut): BullCallSpread — MTM loss > 50% of premium paid
# Trade: T-307. Entry net debit=72.80. 50% = 36.40/unit.
# Pass shortLegLtp=12.50 / longLegLtp=130.00 → net = 12.50-130.00 = -117.50
# MTM loss/unit = 117.50 - 72.80 = 44.70 > 36.40 → EXIT
# ────────────────────────────────────────────────────────────────────────────

h "S3.7 — Expected action: EXIT loss cut (BullCallSpread, MTM > 50% of premium)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_LOSSCUT" \
  -H "Content-Type: application/json" \
  -d '{
    "niftySpot": 23800,
    "vix": 18.2,
    "shortLegLtp": 12.50,
    "longLegLtp":  130.00,
    "shortLegIv":  0.175
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S3.8 — Error path: evaluate expired trade
# Status must be ACTIVE or EXIT_FAILED — CLOSED should return 409
# (Use any trade that's been set to CLOSED — or create one manually)
# ────────────────────────────────────────────────────────────────────────────

h "S3.8 — Error: evaluate CLOSED trade (expect 409 Conflict)"
info "Set a trade to CLOSED first:"
info "UPDATE zupptrade_dev.trades SET status='CLOSED' WHERE id='$T_A3_HOLD';"
info "(revert after test)"
# curl -s -X POST "$A3/api/v1/agent3/evaluate/$T_A3_HOLD" \
#   -H "Content-Type: application/json" \
#   -d '{"niftySpot": 24200, "vix": 18.5}'

h "S3 DONE"
