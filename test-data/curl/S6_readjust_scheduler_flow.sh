#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# S6 — Scheduler READJUST integration test: full 6-step chain for IronCondor
#
# Tests ReadjustmentService.handle() triggered by the real 5-minute scheduler loop,
# NOT by the /evaluate endpoint. This is the only test that exercises the complete
# automated re-entry chain end-to-end:
#
#   Scheduler cycle
#     └─ IronCondorMonitorStrategy.evaluate()  → READJUST (T2_READJUST_PNL)
#           └─ ReadjustmentService.handle()
#                 Step 1: DTE guard (DTE=4 ≥ min=2 → passes)
#                 Step 2: Exit old trade via Agent 5 (simulate-exit=true)
#                 Step 3: Agent 1 fresh signal
#                 Step 4: Agent 2 recommend (relaxed PoP gate: 65%)
#                 Step 5: Agent 2 auto-confirm
#                 Step 6: Agent 5 execute new trade (simulate-fills=true)
#
# WHY READJUST FIRES DETERMINISTICALLY
#   T-401 is seeded with actualNetPremiumPerUnit=1.00 (artificially tiny).
#   The scheduler fetches live PE 24000 LTP from Upstox: at ~24050 spot with DTE=4,
#   that will be ~60–100 Rs. PE spread close cost (PE24000 − PE23900 LTP) ≈ 30–60 Rs.
#   P&L = (1.00 − 30~60) × 5 × 65 = huge loss >> t2LossThreshold=1 → READJUST fires.
#   t3LossThreshold=9999999 ensures P&L never triggers EXIT instead.
#
# PREREQUISITES (run this check before starting)
#   1. SQL seed loaded: psql ... -f 05_seed_readjust_scheduler_test.sql
#   2. All agents running with sandbox profile (simulate-fills=true, simulate-exit=true)
#   3. IST market hours 09:15–15:30 Mon–Fri (scheduler only fires during market hours)
#   4. Valid Upstox access token — scheduler needs option chain fetch for live PE LTPs
#   5. Prior seeds 01–04 loaded (user_profile_id FK)
#
# EXPECTED OUTCOMES
#   FULL SUCCESS:    T-401 → CLOSED, new ACTIVE trade created with fresh IC/spread
#   PARTIAL SUCCESS: T-401 → CLOSED (exit OK), no new ACTIVE trade (A1/A2/A5 step failed)
#   NO FIRE:         T-401 stays ACTIVE (Nifty breached PE 24000 → EXIT fired instead,
#                    OR Upstox token invalid → no live LTPs → snapshot null → WATCH)
#
# TIMING
#   Cron: "0 */5 9-15 * * MON-FRI" — ticks at :00/:05/:10/… past each hour.
#   Script waits up to 6 minutes polling every 30 seconds. One cycle is guaranteed
#   within that window as long as market is open.
# ─────────────────────────────────────────────────────────────────────────────
source "$(dirname "$0")/vars.sh"

TRADE=$T_A3_IC_READJUST   # a3000001-0000-0000-0000-000000000010

# ─────────────────────────────────────────────────────────────────────────────
# PRE-FLIGHT: service health
# ─────────────────────────────────────────────────────────────────────────────

h "S6: Pre-flight checks"

info "Agent 3 health:"
curl -s "$A3/api/v1/agent3/health" | grep -oE '"status":"[^"]+"'
echo ""

info "Agent 1 health:"
curl -s "$A1/api/v1/agent1/health" | grep -oE '"status":"[^"]+"'
echo ""

info "Agent 2 health (GET /actuator/health):"
curl -s "$A2/actuator/health" | grep -oE '"status":"[^"]+"' | head -1
echo ""

info "Agent 5 health (GET /actuator/health):"
curl -s "$A5/actuator/health" | grep -oE '"status":"[^"]+"' | head -1
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# STEP 1: Confirm T-401 is in active trades
# ─────────────────────────────────────────────────────────────────────────────

h "Step 1: Verify T-401 (IC READJUST calibrated) is ACTIVE"

ACTIVE_RESP=$(curl -s "$A3/api/v1/agent3/active-trades")
if echo "$ACTIVE_RESP" | grep -q "$TRADE"; then
    ok "T-401 found in active-trades — seed is loaded"
else
    echo ""
    echo "  ✗ T-401 NOT found in active-trades."
    echo "    Either the seed hasn't been loaded or T-401 is already CLOSED from a previous run."
    echo ""
    echo "    Load the seed:  psql -d nifty_trading -c 'SET search_path TO zupptrade_dev;'"
    echo "                    then run 05_seed_readjust_scheduler_test.sql"
    echo ""
    echo "    If it ran before and T-401 is CLOSED — re-run the seed to reset it to ACTIVE."
    echo ""
    exit 1
fi
echo ""

# Show current T-401 details from active-trades
info "T-401 monitor snapshot at start:"
echo "$ACTIVE_RESP" | python3 -c "
import sys, json
trades = json.load(sys.stdin)
for t in trades:
    mc = t.get('monitorConfig') or {}
    tid = t.get('tradeId','')
    if '000000000010' in tid:
        print(f'  tradeId:    {tid}')
        print(f'  strategy:   {t.get(\"strategy\",\"—\")}')
        print(f'  status:     {t.get(\"status\",\"—\")}')
        print(f'  netPremium: {mc.get(\"actualNetPremiumPerUnit\",\"—\")}  ← should be 1.00')
        thr = mc.get('thresholds',{})
        print(f'  t2Loss:     {thr.get(\"t2LossThreshold\",\"—\")}  ← should be 1')
        print(f'  t3Loss:     {thr.get(\"t3LossThreshold\",\"—\")}  ← should be 9999999')
" 2>/dev/null || info "(python3 not available — check active-trades JSON manually for tradeId ending 0010)"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# STEP 2: Wait for the scheduler to fire READJUST
#
# Scheduler cron: "0 */5 9-15 * * MON-FRI"
# Polls every 30s. Max wait: 360s (6 min) — guarantees at least one full cycle.
# T-401 disappears from active-trades as soon as status changes off ACTIVE.
# ─────────────────────────────────────────────────────────────────────────────

h "Step 2: Waiting for scheduler READJUST (max 6 min, polling every 30s)"
info "Scheduler fires at :00/:05/:10/… past each hour. Waiting…"
echo ""

ELAPSED=0
MAX_WAIT=360
FIRED=0

while [ $ELAPSED -lt $MAX_WAIT ]; do
    sleep 30
    ELAPSED=$((ELAPSED + 30))

    ACTIVE_NOW=$(curl -s "$A3/api/v1/agent3/active-trades")
    if ! echo "$ACTIVE_NOW" | grep -q "$TRADE"; then
        FIRED=1
        ok "T-401 is no longer in active-trades at ${ELAPSED}s elapsed — READJUST fired!"
        break
    fi
    info "${ELAPSED}s elapsed — T-401 still ACTIVE, waiting for next scheduler tick…"
done

if [ $FIRED -eq 0 ]; then
    echo ""
    echo "  ✗ T-401 still ACTIVE after ${MAX_WAIT}s. Possible causes:"
    echo "    • Scheduler not running (check market hours / cron config / shedlock)"
    echo "    • Upstox token invalid → option chain empty → snapshot null → WATCH (not READJUST)"
    echo "    • Nifty breached PE 24000 → T3_SHORT_STRIKE_BREACH fired EXIT first"
    echo "      (trade exits but with EXIT status, not CLOSED yet — check trade status in DB)"
    echo ""
    echo "    Check Agent 3 logs: docker logs agent3-monitor 2>&1 | grep -E 'readjust|scheduler'"
    echo ""
fi
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# STEP 3: Verify outcome
# ─────────────────────────────────────────────────────────────────────────────

h "Step 3: Outcome verification"

info "Active trades after scheduler cycle:"
FINAL_ACTIVE=$(curl -s "$A3/api/v1/agent3/active-trades")
echo "$FINAL_ACTIVE" | python3 -c "
import sys, json
trades = json.load(sys.stdin)
if not trades:
    print('  (no active trades — all positions closed or not yet re-entered)')
else:
    for t in trades:
        mc = t.get('monitorConfig') or {}
        print(f'  {t.get(\"tradeId\",\"—\")[:36]}  {t.get(\"strategy\",\"—\"):20s}  {t.get(\"status\",\"—\")}')
" 2>/dev/null || echo "$FINAL_ACTIVE" | grep -oE '"tradeId":"[^"]+"|"strategy":"[^"]+"|"status":"[^"]+"'
echo ""

info "Check Agent 3 logs for the READJUST sequence:"
info "  docker logs agent3-monitor 2>&1 | grep -E 'readjust|READJUST|T2_READJUST'"
echo ""
info "Expected log sequence:"
info "  readjust.triggered tradeId=...0010 tradeCode=T-20260703-0401"
info "  readjust.exit.success tradeId=...0010"
info "  readjust.complete oldTradeId=...0010 newTradeId=<new-uuid> strategy=<new-strategy>"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# STEP 4: SQL verification queries (run manually in DB client)
# ─────────────────────────────────────────────────────────────────────────────

h "Step 4: SQL verification — run these in your DB client"

info "── A. T-401 final status (should be CLOSED on full success) ──"
cat << 'EOF'
  SELECT id, status, close_reason, closed_at, actual_pnl, trade_code
  FROM zupptrade_dev.trades
  WHERE id = 'a3000001-0000-0000-0000-000000000010';
EOF
echo ""

info "── B. Trade ledger for T-401 (should show CLOSE_INITIATED + CLOSED events) ──"
cat << 'EOF'
  SELECT event_type, created_at, source, payload
  FROM zupptrade_dev.trade_ledger
  WHERE trade_id = 'a3000001-0000-0000-0000-000000000010'
  ORDER BY created_at;
EOF
echo ""

info "── C. New ACTIVE trade created by ReadjustmentService (step 6) ──"
cat << 'EOF'
  SELECT id, strategy, status, trade_code, confirmed_at, created_at
  FROM zupptrade_dev.trades
  WHERE status IN ('ACTIVE','CONFIRMED','PENDING_CONFIRM')
    AND created_at > NOW() - INTERVAL '10 minutes'
    AND id::text NOT LIKE 'a%'    -- exclude all seeded UUIDs
  ORDER BY created_at DESC
  LIMIT 5;
EOF
echo ""

info "── D. Notifications / alerts fired during readjust ──"
cat << 'EOF'
  SELECT alert_type, message, created_at
  FROM zupptrade_dev.notifications
  WHERE trade_id = 'a3000001-0000-0000-0000-000000000010'
  ORDER BY created_at;
EOF
echo ""

info "── E. Monitoring evaluations logged for T-401 ──"
cat << 'EOF'
  SELECT action, threshold_hit, reason, mark_to_market_pnl, evaluated_at
  FROM zupptrade_dev.monitoring_evaluations
  WHERE trade_id = 'a3000001-0000-0000-0000-000000000010'
  ORDER BY evaluated_at;
EOF
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# STEP 5: Interpret results
# ─────────────────────────────────────────────────────────────────────────────

h "Step 5: Expected outcomes"
echo ""
echo "  FULL SUCCESS (all 6 steps passed):"
echo "    • T-401 status = CLOSED"
echo "    • trade_ledger has TRADE_CLOSE_INITIATED + TRADE_CLOSED events"
echo "    • New ACTIVE trade in DB (query C above) with fresh strategy"
echo "    • notifications has readjust_success alert"
echo ""
echo "  PARTIAL SUCCESS (exit OK, re-entry failed at step 3/4/5/6):"
echo "    • T-401 status = CLOSED"
echo "    • No new ACTIVE trade (Agent 1 scored SKIP, or Agent 2 gates failed)"
echo "    • notifications has readjust_no_reentry or readjust_agent1_failed warning"
echo "    • This is valid — ReadjustmentService correctly gave up on re-entry"
echo ""
echo "  EXIT INSTEAD OF READJUST:"
echo "    • T-401 status = CLOSED (or EXIT_FAILED)"
echo "    • monitoring_evaluations shows action=EXIT, reason=T3_SHORT_STRIKE_BREACH"
echo "    • Means Nifty breached PE 24000 during the window"
echo "    • Re-run seed after Nifty recovers above 24000"
echo ""
echo "  NO FIRE — Upstox token issue:"
echo "    • T-401 still ACTIVE"
echo "    • monitoring_evaluations shows action=WATCH, reason='Market data unavailable'"
echo "    • Refresh Upstox token and re-run"
echo ""

h "S6 Scheduler READJUST test DONE"
