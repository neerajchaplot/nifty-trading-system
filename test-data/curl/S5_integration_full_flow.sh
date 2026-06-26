#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# S5 — Integration: full flow tests
#
# Covers 4 complete flows using pre-seeded signals + captured option chain:
#   F1: Bullish signal → BullPutSpread → confirm → execute → monitor (HOLD)
#   F2: Bullish signal → BullPutSpread → confirm → execute → monitor (EXIT price)
#   F3: VIX Extreme signal → SKIP (no trade generated)
#   F4: Bearish signal → BearCallSpread → confirm → execute → monitor (HOLD)
#
# Pre-requisites:
#   1. All SQL seeds loaded (01–04)
#   2. Agent 2 running with Friday's option chain data (see capture/replay below)
#      OR use pre-seeded CONFIRMED trades (T-207, T-208) and skip the recommend step.
#   3. Agent 5 running with sandbox profile (simulate-fills=true)
#   4. Agent 3 running
# ─────────────────────────────────────────────────────────────────────────────
source "$(dirname "$0")/vars.sh"

# ─────────────────────────────────────────────────────────────────────────────
# OPTION A: Run recommend via live Agent 2 (requires Friday option chain)
# Uncomment if Agent 2 is configured with captured option chain data.
# ─────────────────────────────────────────────────────────────────────────────

recommend_and_confirm() {
    local SIGNAL_ID=$1
    local USER_ID=$2
    local LABEL=$3

    h "Recommend: $LABEL"
    RESP=$(curl -s -X POST "$A2/api/v1/agent2/recommend" \
      -H "Content-Type: application/json" \
      -d "{\"agent1SignalId\":\"$SIGNAL_ID\",\"userProfileId\":\"$USER_ID\"}")
    echo "$RESP" | grep -E '"tradeId"|"strategy"|"status"'
    TRADE_ID=$(echo "$RESP" | grep -o '"tradeId":"[^"]*"' | cut -d'"' -f4)
    echo "  → tradeId: $TRADE_ID"
    echo "$TRADE_ID"
}

# ─────────────────────────────────────────────────────────────────────────────
# F1: Happy path — Bullish Mild → BullPutSpread → HOLD
# Uses pre-seeded confirmed trade T-207 (skips recommend step)
# ─────────────────────────────────────────────────────────────────────────────

h "F1: Full flow — BullPutSpread → confirm → execute → monitor HOLD"

TRADE=$T_BPS_CONF  # pre-seeded CONFIRMED BullPutSpread

info "Step 1: Execute (Agent 5 — simulate-fills)"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$TRADE\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_PE_23800\",\"optionType\":\"PE\",\"strike\":23800,\"action\":\"SELL\",\"limitPrice\":72.50,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_PE_23700\", \"optionType\":\"PE\",\"strike\":23700,\"action\":\"BUY\", \"limitPrice\":48.30,\"quantity\":520}
    ]
  }" | grep -E '"executionStatus"|"actualNetPremiumPerUnit"|"slippageAlert"'
echo ""

info "Step 2: Evaluate (Agent 3 — spot above T1 → HOLD)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$TRADE" \
  -H "Content-Type: application/json" \
  -d '{"niftySpot":24200,"vix":20.5,"shortLegLtp":22.50,"longLegLtp":10.20,"shortLegIv":0.185}' \
  | grep -E '"action"|"reason"|"markToMarketPnl"'
echo ""

info "Step 3: Verify DB status = ACTIVE"
info "SQL: SELECT status, entry_fills FROM zupptrade_dev.trades WHERE id='$TRADE';"

# ─────────────────────────────────────────────────────────────────────────────
# F2: BullPutSpread → confirm → execute → monitor EXIT (price breach)
# Reset T_BPS_CONF to CONFIRMED, re-execute, then trigger EXIT threshold
# ─────────────────────────────────────────────────────────────────────────────

h "F2: Full flow — BullPutSpread → execute → monitor EXIT (price)"
info "Reset trade to CONFIRMED first:"
info "UPDATE zupptrade_dev.trades SET status='CONFIRMED', entry_fills=NULL, confirmed_at=NOW() WHERE id='$T_BPS_CONF';"

info "Step 1: Re-execute"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$TRADE\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_PE_23800\",\"optionType\":\"PE\",\"strike\":23800,\"action\":\"SELL\",\"limitPrice\":72.50,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_PE_23700\", \"optionType\":\"PE\",\"strike\":23700,\"action\":\"BUY\", \"limitPrice\":48.30,\"quantity\":520}
    ]
  }" | grep '"executionStatus"'
echo ""

info "Step 2: Evaluate with spot below short strike → EXIT"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$TRADE" \
  -H "Content-Type: application/json" \
  -d '{"niftySpot":23450,"vix":22.0,"shortLegLtp":115.80,"longLegLtp":72.40,"shortLegIv":0.238}' \
  | grep -E '"action"|"reason"'
echo ""

info "Step 3: Exit via Agent 5"
curl -s -X POST "$A5/api/v1/agent5/exit" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$TRADE\",
    \"reason\": \"T3_EXIT_PRICE_BREACH\",
    \"exitLegs\": [
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_PE_23800\",\"originalAction\":\"SELL\",\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_PE_23700\", \"originalAction\":\"BUY\",\"quantity\":520}
    ]
  }" | grep -E '"status"|"closeReason"'
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# F3: VIX Extreme — no trade (signal S08 should produce SKIP)
# ─────────────────────────────────────────────────────────────────────────────

h "F3: VIX Extreme path — recommend should return SKIP"
info "Signal: S08 (VIX=27.5, EXTREME). Agent 2 must refuse to recommend."
curl -s -X POST "$A2/api/v1/agent2/recommend" \
  -H "Content-Type: application/json" \
  -d "{\"agent1SignalId\":\"$SIG_SKIP_VEXT\",\"userProfileId\":\"$UP_10L\"}" \
  | grep -E '"strategy"|"status"|"reason"|"skipReason"'
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# F4: Bearish signal → BearCallSpread → confirm → execute → monitor HOLD
# ─────────────────────────────────────────────────────────────────────────────

h "F4: Full flow — BearCallSpread → confirm → execute → monitor HOLD"

TRADE_BCAS=$T_BCAS_PEND

info "Step 1: Confirm BearCallSpread (T-203)"
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
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_CE_24250\",\"optionType\":\"CE\",\"strike\":24250,\"action\":\"SELL\",\"limitPrice\":68.20,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_CE_24350\", \"optionType\":\"CE\",\"strike\":24350,\"action\":\"BUY\", \"limitPrice\":44.80,\"quantity\":520}
    ]
  }" | grep -E '"executionStatus"|"slippageAlert"'
echo ""

info "Step 3: Evaluate (Agent 3 — spot below T1 for BearCallSpread → HOLD)"
info "For BearCallSpread: T1 = short_strike - 150 = 24250-150 = 24100. Spot=23900 < 24100 → HOLD"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$TRADE_BCAS" \
  -H "Content-Type: application/json" \
  -d '{"niftySpot":23900,"vix":20.8,"shortLegLtp":22.50,"longLegLtp":14.80,"shortLegIv":0.192}' \
  | grep -E '"action"|"reason"'
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# F5: IronCondor → confirm → execute → monitor HOLD
# ─────────────────────────────────────────────────────────────────────────────

h "F5: Full flow — IronCondor → confirm → execute → monitor HOLD"
TRADE_IC=$T_IC_PEND

info "Step 1: Confirm IronCondor (T-204)"
curl -s -X POST "$A2/api/v1/agent2/confirm" \
  -H "Content-Type: application/json" \
  -d "{\"tradeId\":\"$TRADE_IC\",\"action\":\"CONFIRM\"}" | grep '"status"'
echo ""

info "Step 2: Execute (IronCondor — 4 legs)"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$TRADE_IC\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_PE_23600\",\"optionType\":\"PE\",\"strike\":23600,\"action\":\"SELL\",\"limitPrice\":55.80,\"quantity\":2925},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_PE_23500\", \"optionType\":\"PE\",\"strike\":23500,\"action\":\"BUY\", \"limitPrice\":38.20,\"quantity\":2925},
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_CE_24400\",\"optionType\":\"CE\",\"strike\":24400,\"action\":\"SELL\",\"limitPrice\":52.40,\"quantity\":2925},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_CE_24500\", \"optionType\":\"CE\",\"strike\":24500,\"action\":\"BUY\", \"limitPrice\":35.60,\"quantity\":2925}
    ]
  }" | grep -E '"executionStatus"|"slippageAlert"'
echo ""

info "Step 3: Evaluate IronCondor — spot in the middle (HOLD)"
curl -s -X POST "$A3/api/v1/agent3/evaluate/$TRADE_IC" \
  -H "Content-Type: application/json" \
  -d '{"niftySpot":24000,"vix":21.0,"shortLegLtp":18.50,"longLegLtp":9.80,"shortLegIv":0.210}' \
  | grep -E '"action"|"reason"'
echo ""

h "S5 Integration flows DONE"
