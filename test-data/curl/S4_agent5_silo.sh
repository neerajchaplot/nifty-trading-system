#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# S4 — Agent 5 silo tests
#
# Agent 5 must be running with -Dspring.profiles.active=sandbox,local
# (bypass-margin-check=true, simulate-fills=true)
#
# Pre-requisites: Run 03_seed_agent2_trades.sql first.
# Instrument keys in seeds have REPLACE_ placeholders — update them with
# Friday's values OR leave as-is (simulate-fills ignores keys for fill logic).
# ─────────────────────────────────────────────────────────────────────────────
source "$(dirname "$0")/vars.sh"

h "S4 — Agent 5 silo tests (sandbox + simulate-fills)"

# ────────────────────────────────────────────────────────────────────────────
# S4.1 — Happy path: BullPutSpread execute
# Trade T-207 is already CONFIRMED. Legs: SELL PE 23800, BUY PE 23700.
# Expected: status=ACTIVE, fills with SIM- order IDs, slippageAlert=false
# ────────────────────────────────────────────────────────────────────────────

h "S4.1 — Execute BullPutSpread (happy path)"
info "Trade: $T_BPS_CONF | lotSize=65 | lots=8 → quantity=520"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BPS_CONF\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_PE_23800\",\"optionType\":\"PE\",\"strike\":23800,\"action\":\"SELL\",\"limitPrice\":72.50,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_PE_23700\", \"optionType\":\"PE\",\"strike\":23700,\"action\":\"BUY\", \"limitPrice\":48.30,\"quantity\":520}
    ]
  }"
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S4.2 — Happy path: BullCallSpread execute
# Trade T-208 is already CONFIRMED. Legs: BUY CE 24200, SELL CE 24400.
# Expected: status=ACTIVE, actualNetPremiumPerUnit negative (debit), slippageAlert=false
# ────────────────────────────────────────────────────────────────────────────

h "S4.2 — Execute BullCallSpread (debit spread — verify no false slippage alert)"
info "Trade: $T_BCS_CONF | lots=2 → quantity=130"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BCS_CONF\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_CE_24200\", \"optionType\":\"CE\",\"strike\":24200,\"action\":\"BUY\", \"limitPrice\":158.40,\"quantity\":130},
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_CE_24400\",\"optionType\":\"CE\",\"strike\":24400,\"action\":\"SELL\",\"limitPrice\":85.60,\"quantity\":130}
    ]
  }"
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S4.3 — Slippage alert: actual net worse than expected × 0.90
# Simulate by submitting limitPrices that differ from stored netPremiumPerUnit.
# Stored netPremiumPerUnit = 24.20.
# Pass SELL at 68.00 and BUY at 51.20 → net = 68.00-51.20 = 16.80
# 16.80 < 24.20 × 0.90 = 21.78 → slippageAlert=true
# Note: re-seed T_BPS_CONF before running this (it's ACTIVE after S4.1)
# ────────────────────────────────────────────────────────────────────────────

h "S4.3 — Slippage alert (actual net 16.80 vs expected 24.20)"
info "Re-seed T-207 to CONFIRMED before running:"
info "UPDATE zupptrade_dev.trades SET status='CONFIRMED' WHERE id='$T_BPS_CONF';"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BPS_CONF\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_PE_23800\",\"optionType\":\"PE\",\"strike\":23800,\"action\":\"SELL\",\"limitPrice\":68.00,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_PE_23700\", \"optionType\":\"PE\",\"strike\":23700,\"action\":\"BUY\", \"limitPrice\":51.20,\"quantity\":520}
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
# Expected: rejection reason "Trade not found or not in CONFIRMED status"
# (T_BPS_CONF is ACTIVE after S4.1 — do NOT re-seed before this test)
# ────────────────────────────────────────────────────────────────────────────

h "S4.5 — Error: attempt to execute already-ACTIVE trade"
curl -s -X POST "$A5/api/v1/agent5/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BPS_CONF\",
    \"legs\": [
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_PE_23800\",\"optionType\":\"PE\",\"strike\":23800,\"action\":\"SELL\",\"limitPrice\":72.50,\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_PE_23700\", \"optionType\":\"PE\",\"strike\":23700,\"action\":\"BUY\", \"limitPrice\":48.30,\"quantity\":520}
    ]
  }"
echo ""

# ────────────────────────────────────────────────────────────────────────────
# S4.6 — Exit flow: close the BullPutSpread opened in S4.1
# After S4.1 the trade is ACTIVE. Now close it.
# Expected: status=CLOSED, exit fills with SIM-X- order IDs
# ────────────────────────────────────────────────────────────────────────────

h "S4.6 — Exit BullPutSpread (reverse legs, market order)"
info "Run AFTER S4.1 (trade must be ACTIVE)"
curl -s -X POST "$A5/api/v1/agent5/exit" \
  -H "Content-Type: application/json" \
  -d "{
    \"tradeId\": \"$T_BPS_CONF\",
    \"reason\": \"MANUAL_TEST_EXIT\",
    \"exitLegs\": [
      {\"instrumentKey\":\"NSE_FO|REPLACE_SELL_PE_23800\",\"originalAction\":\"SELL\",\"quantity\":520},
      {\"instrumentKey\":\"NSE_FO|REPLACE_BUY_PE_23700\", \"originalAction\":\"BUY\", \"quantity\":520}
    ]
  }"
echo ""

h "S4 DONE"
