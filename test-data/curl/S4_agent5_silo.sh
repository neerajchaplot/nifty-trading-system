#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# S4 — Agent 5 silo tests
#
# Agent 5 must be running with -Dspring.profiles.active=sandbox,local
# (bypass-margin-check=true, simulate-fills=true)
#
# Pre-requisites: Run 03_seed_agent2_trades.sql first.
#
# Instrument keys from Friday 2026-06-27 capture (expiry 2026-06-30, ATM=24050):
#   NSE_FO|71473 = PE 24000  LTP=64.50   (short leg BullPutSpread)
#   NSE_FO|79723 = PE 23900  LTP=38.15   (long  leg BullPutSpread)
#   NSE_FO|79732 = CE 24100  LTP=102.45  (long  leg BullCallSpread)
#   NSE_FO|79738 = CE 24250  LTP=43.55   (short leg BullCallSpread)
# ─────────────────────────────────────────────────────────────────────────────
source "$(dirname "$0")/vars.sh"

h "S4 — Agent 5 silo tests (sandbox + simulate-fills)"

# ────────────────────────────────────────────────────────────────────────────
# S4.1 — Happy path: BullPutSpread execute
# Trade T-207 is already CONFIRMED.
# Legs: SELL PE 24000 (NSE_FO|71473), BUY PE 23900 (NSE_FO|79723).
# Expected: status=ACTIVE, fills with SIM- order IDs, slippageAlert=false
#   actualNet=64.50-38.15=26.35 = expectedNet → no slippage
# ────────────────────────────────────────────────────────────────────────────

h "S4.1 — Execute BullPutSpread (happy path)"
info "Trade: $T_BPS_CONF | lotSize=65 | lots=8 → quantity=520"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BPS_CONF\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|71473\",\"optionType\":\"PE\",\"strike\":24000,\"action\":\"SELL\",\"limitPrice\":64.50,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|79723\",\"optionType\":\"PE\",\"strike\":23900,\"action\":\"BUY\", \"limitPrice\":38.15,\"quantity\":520}
    ]
  }"
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S4.2 — Happy path: BullCallSpread execute
# Trade T-208 is already CONFIRMED.
# Legs: BUY CE 24100 (NSE_FO|79732), SELL CE 24250 (NSE_FO|79738).
# Expected: status=ACTIVE, actualNetPremiumPerUnit negative (debit=-58.90), slippageAlert=false
#   Debit spread: actualNet = -(102.45-43.55) = -58.90 = expectedNet → no slippage
# ────────────────────────────────────────────────────────────────────────────

h "S4.2 — Execute BullCallSpread (debit spread — verify no false slippage alert)"
info "Trade: $T_BCS_CONF | lots=2 → quantity=130"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BCS_CONF\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|79732\",\"optionType\":\"CE\",\"strike\":24100,\"action\":\"BUY\", \"limitPrice\":102.45,\"quantity\":130},
      {\"instrumentKey\":\"NSE_FO|79738\",\"optionType\":\"CE\",\"strike\":24250,\"action\":\"SELL\",\"limitPrice\":43.55,\"quantity\":130}
    ]
  }"
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S4.3 — Slippage alert: actual net worse than expected × 0.90
# Stored summary.netPremiumPerUnit=26.35 for T-207.
# Pass SELL at 56.00, BUY at 44.00 → actualNet = 56.00-44.00 = 12.00
# 12.00 < 26.35 × 0.90 = 23.72 → slippageAlert=true
# Note: re-seed T-207 to CONFIRMED before running (it's ACTIVE after S4.1):
#   UPDATE zupptrade_dev.trades SET status='CONFIRMED' WHERE id='$T_BPS_CONF';
# ────────────────────────────────────────────────────────────────────────────

h "S4.3 — Slippage alert (actualNet 12.00 vs expectedNet 26.35, threshold 23.72)"
info "Re-seed T-207 to CONFIRMED before running:"
info "UPDATE zupptrade_dev.trades SET status='CONFIRMED' WHERE id='$T_BPS_CONF';"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BPS_CONF\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|71473\",\"optionType\":\"PE\",\"strike\":24000,\"action\":\"SELL\",\"limitPrice\":56.00,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|79723\",\"optionType\":\"PE\",\"strike\":23900,\"action\":\"BUY\", \"limitPrice\":44.00,\"quantity\":520}
    ]
  }"
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S4.4 — Error path: non-existent tradeId
# Expected: 404 or rejection reason "Trade not found"
# ────────────────────────────────────────────────────────────────────────────

h "S4.4 — Error: unknown tradeId"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d '{
    "tradeId": "99999999-9999-9999-9999-999999999999",
    "legs": [
      {"instrumentKey":"NSE_FO|DUMMY","optionType":"CE","strike":24000,"action":"BUY","limitPrice":100.00,"quantity":65}
    ]
  }'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S4.5 — Error path: trade already ACTIVE (double execute)
# Expected: rejection "Trade not found or not in CONFIRMED status"
# (T_BPS_CONF is ACTIVE after S4.1 — do NOT re-seed before this test)
# ────────────────────────────────────────────────────────────────────────────

h "S4.5 — Error: attempt to execute already-ACTIVE trade"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BPS_CONF\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|71473\",\"optionType\":\"PE\",\"strike\":24000,\"action\":\"SELL\",\"limitPrice\":64.50,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|79723\",\"optionType\":\"PE\",\"strike\":23900,\"action\":\"BUY\", \"limitPrice\":38.15,\"quantity\":520}
    ]
  }"
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S4.6 — Exit flow: close the BullPutSpread opened in S4.1
# After S4.1 the trade is ACTIVE. Now close it.
# Reverse legs: original SELL → BUY to close, original BUY → SELL to close.
# Expected: status=CLOSED, exit fills with SIM-X- order IDs
# ────────────────────────────────────────────────────────────────────────────

h "S4.6 — Exit BullPutSpread (reverse legs, market order)"
info "Run AFTER S4.1 (trade must be ACTIVE)"
curl -s -X POST "$A5/api/v1/agent5/exit/$T_BPS_CONF" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BPS_CONF\",
    \"reason\": \"MANUAL_TEST_EXIT\",
    \"exitLegs\": [
      {\"instrumentKey\":\"NSE_FO|71473\",\"originalAction\":\"SELL\",\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|79723\",\"originalAction\":\"BUY\", \"quantity\":520}
    ]
  }"
echo ""

h "S4 DONE"
