#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# S5 — Integration: full flow tests
#
# Covers 5 complete flows using pre-seeded signals and confirmed trades:
#   F1: BullPutSpread → execute → monitor-config → evaluate HOLD
#   F2: BullPutSpread → (re-execute) → monitor-config → evaluate EXIT → exit
#   F3: VIX Extreme signal → NO_TRADE (no trade generated)
#   F4: BearCallSpread → confirm → execute → monitor-config → evaluate HOLD
#   F5: IronCondor → confirm → execute → monitor WATCH (CE side at T1)
#
# Instrument keys (2026-07-04 capture, expiry 2026-07-07, ATM=24250):
#   NSE_FO|44621 = PE 24000  LTP=15.65
#   NSE_FO|44617 = PE 23900  LTP=9.30
#   NSE_FO|44615 = PE 23850  LTP=7.15
#   NSE_FO|44633 = CE 24100  LTP=209.55
#   NSE_FO|44635 = CE 24150  LTP=169.20
#   NSE_FO|44642 = CE 24250  LTP=102.15
#
# Pre-requisites:
#   1. All SQL seeds loaded (01–04)
#   2. Re-seed 03_seed_agent2_trades.sql if S4 has already run
#      (S4.1 executes T-207 to ACTIVE — F1 needs it CONFIRMED)
#   3. Agent 5 running with sandbox profile (simulate-fills=true)
#   4. Agent 2 and Agent 3 running
# ─────────────────────────────────────────────────────────────────────────────
source "$(dirname "$0")/vars.sh"

# ─────────────────────────────────────────────────────────────────────────────
# F1: BullPutSpread → execute → monitor HOLD
# Uses pre-seeded CONFIRMED trade T-207 (SELL PE 24000 / BUY PE 23900).
# Thresholds: T1=24150, T2=24075, T3=24000.
# Send spot=24300 > T1=24150 → HOLD.
# ─────────────────────────────────────────────────────────────────────────────

h "F1: Full flow — BullPutSpread → execute → monitor HOLD (spot 24300 > T1=24150)"

TRADE=$T_BPS_CONF  # pre-seeded CONFIRMED BullPutSpread (T-207)

info "Step 1: Execute (Agent 5 — simulate-fills)"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$TRADE\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|44621\",\"optionType\":\"PE\",\"strike\":24000,\"action\":\"SELL\",\"limitPrice\":64.50,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|44617\",\"optionType\":\"PE\",\"strike\":23900,\"action\":\"BUY\", \"limitPrice\":38.15,\"quantity\":520}
    ]
  }" | grep -E '"executionStatus"|"actualNetPremiumPerUnit"|"slippageAlert"'
echo ""

info "Step 1.5: Build monitor-config (required before Agent 3 can evaluate)"
curl -s "$A2/api/v1/agent2/monitor-config/$TRADE" \
  -H "X-Short-Fill-Price: 64.50" \
  -H "X-Long-Fill-Price: 38.15" | grep -E '"actualNetPremiumPerUnit"|"slippageAlert"'
echo ""

info "Step 2: Evaluate (Agent 3 — spot 24300 above T1=24150 → HOLD)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$TRADE" \
  -H "Content-Type: application/json" \
  -d '{"niftySpot":24300,"vix":17.5,"shortLegLtp":28.00,"longLegLtp":12.00,"shortLegIv":0.185}' \
  | grep -E '"action"|"reason"|"markToMarketPnl"'
echo ""

info "Step 3: Verify DB status = ACTIVE"
info "SQL: SELECT status, entry_fills FROM zupptrade_dev.trades WHERE id='$TRADE';"

# ─────────────────────────────────────────────────────────────────────────────
# F2: BullPutSpread → re-execute → monitor EXIT (price breach)
# T-207 is ACTIVE after F1 — Step 1 will show REJECTED (already ACTIVE).
# monitor-config step still runs and updates the config.
# Evaluate with spot below T3=24000 → EXIT, then close via Agent 5.
# ─────────────────────────────────────────────────────────────────────────────

h "F2: Full flow — BullPutSpread → execute → monitor EXIT (spot 23950 < T3=24000)"
info "Note: Step 1 shows REJECTED if T-207 is already ACTIVE from F1 — expected."
info "To get a clean execute, re-seed 03_seed first then re-run."

info "Step 1: Re-execute (may reject if already ACTIVE)"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$TRADE\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|44621\",\"optionType\":\"PE\",\"strike\":24000,\"action\":\"SELL\",\"limitPrice\":64.50,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|44617\",\"optionType\":\"PE\",\"strike\":23900,\"action\":\"BUY\", \"limitPrice\":38.15,\"quantity\":520}
    ]
  }" | grep '"executionStatus"'
echo ""

info "Step 1.5: Build monitor-config (works regardless of ACTIVE/CONFIRMED state)"
curl -s "$A2/api/v1/agent2/monitor-config/$TRADE" \
  -H "X-Short-Fill-Price: 64.50" \
  -H "X-Long-Fill-Price: 38.15" | grep -E '"actualNetPremiumPerUnit"|"slippageAlert"'
echo ""

info "Step 2: Evaluate with spot 23950 < T3=24000 → EXIT"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$TRADE" \
  -H "Content-Type: application/json" \
  -d '{"niftySpot":23950,"vix":22.0,"shortLegLtp":148.00,"longLegLtp":105.00,"shortLegIv":0.238}' \
  | grep -E '"action"|"reason"'
echo ""

info "Step 3: Exit via Agent 5"
curl -s -X POST "$A5/api/v1/agent5/exit/$TRADE" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$TRADE\",
    \"reason\": \"T3_EXIT_PRICE_BREACH\",
    \"exitLegs\": [
      {\"instrumentKey\":\"NSE_FO|44621\",\"originalAction\":\"SELL\",\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|44617\",\"originalAction\":\"BUY\", \"quantity\":520}
    ]
  }" | grep -E '"status"|"closeReason"'
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# F3: VIX Extreme — no trade (signal S08 VIX=27.5 → NO_TRADE)
# Agent 2 returns strategy=NO_TRADE / status=REJECTED — no position opened.
# ─────────────────────────────────────────────────────────────────────────────

h "F3: VIX Extreme path — recommend returns NO_TRADE (VIX=27.5 Extreme)"
curl -s -X POST "$A2/api/v1/agent2/recommend" \
  -H "Content-Type: application/json" \
  -d "{\"agent1SignalId\":\"$SIG_SKIP_VEXT\",\"userProfileId\":\"$UP_10L\"}" \
  | grep -E '"strategy"|"status"|"reason"|"skipReason"'
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# F4: BearCallSpread → confirm → execute → monitor HOLD
# Confirm pre-seeded PENDING T-203 (SELL CE 24150 / BUY CE 24250).
# Thresholds: T1=24000 (short-150), T2=24075 (short-75), T3=24150 (breach).
# Send spot=23900 < T1=24000 → HOLD (Nifty hasn't risen to T1 yet).
# ─────────────────────────────────────────────────────────────────────────────

h "F4: Full flow — BearCallSpread → confirm → execute → monitor HOLD (spot 23900 < T1=24000)"

TRADE_BCAS=$T_BCAS_PEND  # T-203 PENDING BearCallSpread

info "Step 1: Confirm BearCallSpread (T-203)"
info "409 is expected if S2 silo already confirmed this trade."
CONF_RESP=$(curl -s -X POST "$A2/api/v1/agent2/confirm" \
  -H "Content-Type: application/json" \
  -d "{\"tradeId\":\"$TRADE_BCAS\",\"action\":\"CONFIRM\"}")
echo "$CONF_RESP" | grep '"status"'
echo ""

info "Step 2: Execute (Agent 5)"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$TRADE_BCAS\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|44635\",\"optionType\":\"CE\",\"strike\":24150,\"action\":\"SELL\",\"limitPrice\":78.10,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|44642\",\"optionType\":\"CE\",\"strike\":24250,\"action\":\"BUY\", \"limitPrice\":43.55,\"quantity\":520}
    ]
  }" | grep -E '"executionStatus"|"slippageAlert"'
echo ""

info "Step 2.5: Build monitor-config (required before Agent 3 can evaluate)"
curl -s "$A2/api/v1/agent2/monitor-config/$TRADE_BCAS" \
  -H "X-Short-Fill-Price: 78.10" \
  -H "X-Long-Fill-Price: 43.55" | grep -E '"actualNetPremiumPerUnit"|"slippageAlert"'
echo ""

info "Step 3: Evaluate (Agent 3 — spot 23900 < T1=24000 → HOLD)"
info "BearCallSpread: HOLD when spot < T1_CE. T1=24150-150=24000. Spot=23900 < 24000 → HOLD."
curl -s -X POST "$A3/api/v1/agent3/evaluate/$TRADE_BCAS" \
  -H "Content-Type: application/json" \
  -d '{"niftySpot":23900,"vix":20.8,"shortLegLtp":52.00,"longLegLtp":28.50,"shortLegIv":0.192}' \
  | grep -E '"action"|"reason"'
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# F5: IronCondor → confirm → execute → monitor (WATCH expected)
# Confirm pre-seeded PENDING T-204 (4-leg IC).
# Legs: SELL PE 23900 / BUY PE 23850 / SELL CE 24150 / BUY CE 24250.
# Lots: 40. Qty per leg: 2600.
#
# monitor-config uses fixed fallback (no option chain at config time):
#   T1_DOWN (PE side) = 23900+150 = 24050  → WATCH if spot ≤ 24050
#   T2_DOWN (PE side) = 23900+75  = 23975  → READJUST if spot ≤ 23975
#   T1_UP   (CE side) = 24150-150 = 24000  → WATCH if spot ≥ 24000
#   T2_UP   (CE side) = 24150-75  = 24075  → READJUST if spot ≥ 24075
#
# Send spot=24060 (between CE T1=24000 and CE T2=24075):
#   PE side: 24060 > T2_DOWN=23975 and 24060 > T1_DOWN=24050 → PE HOLD ✓
#   CE side: 24060 ≥ T1_UP=24000 → CE T1 triggered; 24060 < T2_UP=24075 → T2 NOT triggered ✓
# Expected: WATCH (CE T1 only — spot is inside CE T1/T2 buffer zone)
#
# IC monitor-config is fully supported: 4 fill price headers (PE + CE spreads).
# Step 2.5 builds the config with all 4 legs; Step 3 evaluates both sides.
# ─────────────────────────────────────────────────────────────────────────────

h "F5: Full flow — IronCondor → confirm → execute → monitor WATCH (CE side at T1)"
TRADE_IC=$T_IC_PEND  # T-204 PENDING IronCondor

info "Step 1: Confirm IronCondor (T-204)"
info "409 is expected if S2 silo already confirmed this trade."
curl -s -X POST "$A2/api/v1/agent2/confirm" \
  -H "Content-Type: application/json" \
  -d "{\"tradeId\":\"$TRADE_IC\",\"action\":\"CONFIRM\"}" | grep '"status"'
echo ""

info "Step 2: Execute (IronCondor — 4 legs, qty=2600 each)"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$TRADE_IC\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|44617\",\"optionType\":\"PE\",\"strike\":23900,\"action\":\"SELL\",\"limitPrice\":38.15,\"quantity\":2600},
      {\"instrumentKey\":\"NSE_FO|44615\",\"optionType\":\"PE\",\"strike\":23850,\"action\":\"BUY\", \"limitPrice\":29.45,\"quantity\":2600},
      {\"instrumentKey\":\"NSE_FO|44635\",\"optionType\":\"CE\",\"strike\":24150,\"action\":\"SELL\",\"limitPrice\":78.10,\"quantity\":2600},
      {\"instrumentKey\":\"NSE_FO|44642\",\"optionType\":\"CE\",\"strike\":24250,\"action\":\"BUY\", \"limitPrice\":43.55,\"quantity\":2600}
    ]
  }" | grep -E '"executionStatus"|"slippageAlert"'
echo ""

info "Step 2.5: Build monitor-config (IronCondor — 4 fill price headers)"
info "X-Short/Long-Fill-Price = PE spread fills; X-CE-Short/Long-Fill-Price = CE spread fills"
curl -s "$A2/api/v1/agent2/monitor-config/$TRADE_IC" \
  -H "X-Short-Fill-Price: 38.15" \
  -H "X-Long-Fill-Price: 29.45" \
  -H "X-CE-Short-Fill-Price: 78.10" \
  -H "X-CE-Long-Fill-Price: 43.55" | grep -E '"actualNetPremiumPerUnit"|"slippageAlert"|"strategy"'
echo ""

info "Step 3: Evaluate IronCondor — spot=24060 (CE T1 zone: 24000 ≤ spot < 24075)"
info "CE T1_UP=24000: 24060 ≥ 24000 → T1 fires. CE T2_UP=24075: 24060 < 24075 → T2 does NOT fire. Expected: WATCH."
curl -s -X POST "$A3/api/v1/agent3/evaluate/$TRADE_IC" \
  -H "Content-Type: application/json" \
  -d '{"niftySpot":24060,"vix":20.5,"shortLegLtp":62.00,"longLegLtp":38.00,"shortLegIv":0.192}' \
  | grep -E '"action"|"reason"'
echo ""

h "S5 Integration flows DONE"
