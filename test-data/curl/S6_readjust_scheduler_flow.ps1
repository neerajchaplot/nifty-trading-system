# ─────────────────────────────────────────────────────────────────────────────
# S6 — Scheduler READJUST integration test (PowerShell): full 6-step chain for IronCondor
#
# Tests ReadjustmentService.handle() triggered by the real 5-minute scheduler loop,
# NOT by the /evaluate endpoint. The complete automated re-entry chain end-to-end:
#   Scheduler cycle → IronCondorMonitorStrategy.evaluate() → READJUST (T2_READJUST_PNL)
#     → ReadjustmentService.handle():
#         1 DTE guard (DTE=4 ≥ min=2)   2 Exit old via Agent 5 (simulate-exit)
#         3 Agent 1 fresh signal        4 Agent 2 recommend (relaxed PoP gate: 65%)
#         5 Agent 2 auto-confirm        6 Agent 5 execute new trade (simulate-fills)
#
# PREREQUISITES
#   1. SQL seed loaded: 05_seed_readjust_scheduler_test.sql
#   2. All agents running with sandbox profile (simulate-fills=true, simulate-exit=true)
#   3. IST market hours 09:15–15:30 Mon–Fri (scheduler only fires during market hours)
#   4. Valid Upstox access token (scheduler needs option chain fetch for live PE LTPs)
#   5. Prior seeds 01–04 loaded (user_profile_id FK)
#
# Cron: "0 */5 9-15 * * MON-FRI". Script waits up to 6 min, polling every 30s.
# Run:  .\S6_readjust_scheduler_flow.ps1
# ─────────────────────────────────────────────────────────────────────────────
. "$PSScriptRoot\vars.ps1"

$TRADE = $T_A3_IC_READJUST   # a3000001-0000-0000-0000-000000000010

# ── PRE-FLIGHT: service health ────────────────────────────────────────────────
h "S6: Pre-flight checks"
info "Agent 3 health:"; Show-Fields (Invoke-Api -Url "$A3/api/v1/agent3/health") @('status')
info "Agent 1 health:"; Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/health") @('status')
info "Agent 2 health:"; Show-Fields (Invoke-Api -Url "$A2/actuator/health") @('status')
info "Agent 5 health:"; Show-Fields (Invoke-Api -Url "$A5/actuator/health") @('status')

# ── STEP 1: Confirm T-401 is in active trades ─────────────────────────────────
h "Step 1: Verify T-401 (IC READJUST calibrated) is ACTIVE"
$active = Invoke-Api -Url "$A3/api/v1/agent3/active-trades"
$found  = $active | Where-Object { $_.tradeId -eq $TRADE }
if ($found) {
    ok "T-401 found in active-trades — seed is loaded"
    $mc = $found.monitorConfig
    info "T-401 monitor snapshot at start:"
    Write-Host ("    tradeId:    {0}" -f $found.tradeId)
    Write-Host ("    strategy:   {0}" -f $found.strategy)
    Write-Host ("    status:     {0}" -f $found.status)
    if ($mc) {
        Write-Host ("    netPremium: {0}  ← should be 1.00" -f $mc.actualNetPremiumPerUnit)
        if ($mc.thresholds) {
            Write-Host ("    t2Loss:     {0}  ← should be 1"       -f $mc.thresholds.t2LossThreshold)
            Write-Host ("    t3Loss:     {0}  ← should be 9999999" -f $mc.thresholds.t3LossThreshold)
        }
    }
}
else {
    Write-Host ""
    Write-Host "  ✗ T-401 NOT found in active-trades." -ForegroundColor Red
    Write-Host "    Load seed 05_seed_readjust_scheduler_test.sql (or re-run it if T-401 is already CLOSED)."
    return
}

# ── STEP 2: Wait for the scheduler to fire READJUST ───────────────────────────
h "Step 2: Waiting for scheduler READJUST (max 6 min, polling every 30s)"
info "Scheduler fires at :00/:05/:10/… past each hour. Waiting…"

$elapsed = 0; $maxWait = 360; $fired = $false
while ($elapsed -lt $maxWait) {
    Start-Sleep -Seconds 30
    $elapsed += 30
    $now = Invoke-Api -Url "$A3/api/v1/agent3/active-trades"
    if (-not ($now | Where-Object { $_.tradeId -eq $TRADE })) {
        $fired = $true
        ok "T-401 is no longer in active-trades at ${elapsed}s elapsed — READJUST fired!"
        break
    }
    info "${elapsed}s elapsed — T-401 still ACTIVE, waiting for next scheduler tick…"
}
if (-not $fired) {
    Write-Host ""
    Write-Host "  ✗ T-401 still ACTIVE after ${maxWait}s. Possible causes:" -ForegroundColor Red
    Write-Host "    • Scheduler not running (market hours / cron / shedlock)"
    Write-Host "    • Upstox token invalid → option chain empty → snapshot null → WATCH (not READJUST)"
    Write-Host "    • Nifty breached PE 24000 → T3_SHORT_STRIKE_BREACH fired EXIT first"
    Write-Host "    Check logs: docker logs agent3-monitor 2>&1 | Select-String 'readjust|scheduler'"
}

# ── STEP 3: Verify outcome ────────────────────────────────────────────────────
h "Step 3: Outcome verification"
info "Active trades after scheduler cycle:"
$finalActive = Invoke-Api -Url "$A3/api/v1/agent3/active-trades"
if (-not $finalActive) {
    Write-Host "  (no active trades — all positions closed or not yet re-entered)"
}
else {
    foreach ($t in $finalActive) {
        Write-Host ("  {0}  {1,-20}  {2}" -f $t.tradeId, $t.strategy, $t.status)
    }
}
Write-Host ""
info "Expected Agent 3 log sequence:"
info "  readjust.triggered tradeId=...0010 tradeCode=T-20260703-0401"
info "  readjust.exit.success tradeId=...0010"
info "  readjust.complete oldTradeId=...0010 newTradeId=<new-uuid> strategy=<new-strategy>"

# ── STEP 4: SQL verification queries (run manually in your DB client) ─────────
h "Step 4: SQL verification — run these in your DB client"
@'
  -- A. T-401 final status (should be CLOSED on full success)
  SELECT id, status, close_reason, closed_at, actual_pnl, trade_code
  FROM zupptrade_dev.trades
  WHERE id = 'a3000001-0000-0000-0000-000000000010';

  -- B. Trade ledger for T-401 (CLOSE_INITIATED + CLOSED events)
  SELECT event_type, created_at, source, payload
  FROM zupptrade_dev.trade_ledger
  WHERE trade_id = 'a3000001-0000-0000-0000-000000000010'
  ORDER BY created_at;

  -- C. New ACTIVE trade created by ReadjustmentService (step 6)
  SELECT id, strategy, status, trade_code, confirmed_at, created_at
  FROM zupptrade_dev.trades
  WHERE status IN ('ACTIVE','CONFIRMED','PENDING_CONFIRM')
    AND created_at > NOW() - INTERVAL '10 minutes'
    AND id::text NOT LIKE 'a%'
  ORDER BY created_at DESC
  LIMIT 5;

  -- D. Notifications / alerts fired during readjust
  SELECT alert_type, message, created_at
  FROM zupptrade_dev.notifications
  WHERE trade_id = 'a3000001-0000-0000-0000-000000000010'
  ORDER BY created_at;

  -- E. Monitoring evaluations logged for T-401
  SELECT action, threshold_hit, reason, mark_to_market_pnl, evaluated_at
  FROM zupptrade_dev.monitoring_evaluations
  WHERE trade_id = 'a3000001-0000-0000-0000-000000000010'
  ORDER BY evaluated_at;
'@ | Write-Host

# ── STEP 5: Interpret results ─────────────────────────────────────────────────
h "Step 5: Expected outcomes"
Write-Host ""
Write-Host "  FULL SUCCESS (all 6 steps passed):"
Write-Host "    • T-401 status = CLOSED"
Write-Host "    • trade_ledger has TRADE_CLOSE_INITIATED + TRADE_CLOSED events"
Write-Host "    • New ACTIVE trade in DB (query C above) with fresh strategy"
Write-Host "    • notifications has readjust_success alert"
Write-Host ""
Write-Host "  PARTIAL SUCCESS (exit OK, re-entry failed at step 3/4/5/6):"
Write-Host "    • T-401 status = CLOSED; no new ACTIVE trade; readjust_no_reentry warning"
Write-Host ""
Write-Host "  EXIT INSTEAD OF READJUST:"
Write-Host "    • monitoring_evaluations shows action=EXIT, reason=T3_SHORT_STRIKE_BREACH"
Write-Host ""
Write-Host "  NO FIRE — Upstox token issue:"
Write-Host "    • T-401 still ACTIVE; action=WATCH, reason='Market data unavailable'"

h "S6 Scheduler READJUST test DONE"
