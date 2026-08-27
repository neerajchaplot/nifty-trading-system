# ─────────────────────────────────────────────────────────────────────────────
# S5 — Integration: full flow tests (PowerShell)
#
# Covers 5 complete flows using pre-seeded signals and confirmed trades:
#   F1: BullPutSpread → execute → monitor-config → evaluate HOLD
#   F2: BullPutSpread → (re-execute) → monitor-config → evaluate EXIT → exit
#   F3: VIX Extreme signal → NO_TRADE (no trade generated)
#   F4: BearCallSpread → confirm → execute → monitor-config → evaluate HOLD
#   F5: IronCondor → confirm → execute → monitor WATCH (CE side at T1)
#
# Instrument keys (2026-07-04 capture, expiry 2026-07-07, ATM=24250):
#   NSE_FO|44621 = PE 24000  |  NSE_FO|44617 = PE 23900  |  NSE_FO|44615 = PE 23850
#   NSE_FO|44633 = CE 24100  |  NSE_FO|44635 = CE 24150  |  NSE_FO|44642 = CE 24250
#
# Pre-requisites:
#   1. All SQL seeds loaded (01–04)
#   2. Re-seed 03_seed_agent2_trades.sql if S4 has already run
#   3. Agent 5 running with sandbox profile (simulate-fills=true)
#   4. Agent 2 and Agent 3 running
# Run:  .\S5_integration_full_flow.ps1
# ─────────────────────────────────────────────────────────────────────────────
. "$PSScriptRoot\vars.ps1"

# ─────────────────────────────────────────────────────────────────────────────
# F1: BullPutSpread → execute → monitor HOLD (spot 24300 > T1=24150)
# ─────────────────────────────────────────────────────────────────────────────
h "F1: Full flow — BullPutSpread → execute → monitor HOLD (spot 24300 > T1=24150)"
$TRADE = $T_BPS_CONF   # pre-seeded CONFIRMED BullPutSpread (T-207)

info "Step 1: Execute (Agent 5 — simulate-fills)"
Show-Fields (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/execute" -Body @{
    tradeId = $TRADE
    legs = @(
        @{ instrumentKey = 'NSE_FO|44621'; optionType = 'PE'; strike = 24000; action = 'SELL'; limitPrice = 64.50; quantity = 520 }
        @{ instrumentKey = 'NSE_FO|44617'; optionType = 'PE'; strike = 23900; action = 'BUY';  limitPrice = 38.15; quantity = 520 }
    )
}) @('executionStatus','actualNetPremiumPerUnit','slippageAlert')

info "Step 1.5: Build monitor-config (required before Agent 3 can evaluate)"
Show-Fields (Invoke-Api -Url "$A2/api/v1/agent2/monitor-config/$TRADE" -Headers @{ 'X-Short-Fill-Price' = '64.50'; 'X-Long-Fill-Price' = '38.15' }) @('actualNetPremiumPerUnit','slippageAlert')

info "Step 2: Evaluate (Agent 3 — spot 24300 above T1=24150 → HOLD)"
Show-Fields (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$TRADE" -Body @{
    niftySpot = 24300; vix = 17.5; shortLegLtp = 28.00; longLegLtp = 12.00; shortLegIv = 0.185 }) @('action','reason','markToMarketPnl')

info "Step 3: Verify DB status = ACTIVE"
info "SQL: SELECT status, entry_fills FROM zupptrade_dev.trades WHERE id='$TRADE';"

# ─────────────────────────────────────────────────────────────────────────────
# F2: BullPutSpread → re-execute → monitor EXIT (spot 23950 < T3=24000)
# ─────────────────────────────────────────────────────────────────────────────
h "F2: Full flow — BullPutSpread → execute → monitor EXIT (spot 23950 < T3=24000)"
info "Note: Step 1 shows REJECTED if T-207 is already ACTIVE from F1 — expected."

info "Step 1: Re-execute (may reject if already ACTIVE)"
Show-Fields (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/execute" -Body @{
    tradeId = $TRADE
    legs = @(
        @{ instrumentKey = 'NSE_FO|44621'; optionType = 'PE'; strike = 24000; action = 'SELL'; limitPrice = 64.50; quantity = 520 }
        @{ instrumentKey = 'NSE_FO|44617'; optionType = 'PE'; strike = 23900; action = 'BUY';  limitPrice = 38.15; quantity = 520 }
    )
}) @('executionStatus')

info "Step 1.5: Build monitor-config (works regardless of ACTIVE/CONFIRMED state)"
Show-Fields (Invoke-Api -Url "$A2/api/v1/agent2/monitor-config/$TRADE" -Headers @{ 'X-Short-Fill-Price' = '64.50'; 'X-Long-Fill-Price' = '38.15' }) @('actualNetPremiumPerUnit','slippageAlert')

info "Step 2: Evaluate with spot 23950 < T3=24000 → EXIT"
Show-Fields (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$TRADE" -Body @{
    niftySpot = 23950; vix = 22.0; shortLegLtp = 148.00; longLegLtp = 105.00; shortLegIv = 0.238 }) @('action','reason')

info "Step 3: Exit via Agent 5"
Show-Fields (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/exit/$TRADE" -Body @{
    tradeId = $TRADE
    reason = 'T3_EXIT_PRICE_BREACH'
    exitLegs = @(
        @{ instrumentKey = 'NSE_FO|44621'; originalAction = 'SELL'; quantity = 520 }
        @{ instrumentKey = 'NSE_FO|44617'; originalAction = 'BUY';  quantity = 520 }
    )
}) @('status','closeReason')

# ─────────────────────────────────────────────────────────────────────────────
# F3: VIX Extreme — no trade (signal S08 VIX=27.5 → NO_TRADE)
# ─────────────────────────────────────────────────────────────────────────────
h "F3: VIX Extreme path — recommend returns NO_TRADE (VIX=27.5 Extreme)"
Show-Fields (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/recommend" -Body @{
    agent1SignalId = $SIG_SKIP_VEXT; userProfileId = $UP_10L }) @('strategy','status','reason','skipReason')

# ─────────────────────────────────────────────────────────────────────────────
# F4: BearCallSpread → confirm → execute → monitor HOLD (spot 23900 < T1=24000)
# ─────────────────────────────────────────────────────────────────────────────
h "F4: Full flow — BearCallSpread → confirm → execute → monitor HOLD (spot 23900 < T1=24000)"
$TRADE_BCAS = $T_BCAS_PEND   # T-203 PENDING BearCallSpread

info "Step 1: Confirm BearCallSpread (T-203) — 409 expected if S2 already confirmed it"
Show-Fields (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $TRADE_BCAS; action = 'CONFIRM' }) @('status')

info "Step 2: Execute (Agent 5)"
Show-Fields (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/execute" -Body @{
    tradeId = $TRADE_BCAS
    legs = @(
        @{ instrumentKey = 'NSE_FO|44635'; optionType = 'CE'; strike = 24150; action = 'SELL'; limitPrice = 78.10; quantity = 520 }
        @{ instrumentKey = 'NSE_FO|44642'; optionType = 'CE'; strike = 24250; action = 'BUY';  limitPrice = 43.55; quantity = 520 }
    )
}) @('executionStatus','slippageAlert')

info "Step 2.5: Build monitor-config"
Show-Fields (Invoke-Api -Url "$A2/api/v1/agent2/monitor-config/$TRADE_BCAS" -Headers @{ 'X-Short-Fill-Price' = '78.10'; 'X-Long-Fill-Price' = '43.55' }) @('actualNetPremiumPerUnit','slippageAlert')

info "Step 3: Evaluate (Agent 3 — spot 23900 < T1=24000 → HOLD)"
Show-Fields (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$TRADE_BCAS" -Body @{
    niftySpot = 23900; vix = 20.8; shortLegLtp = 52.00; longLegLtp = 28.50; shortLegIv = 0.192 }) @('action','reason')

# ─────────────────────────────────────────────────────────────────────────────
# F5: IronCondor → confirm → execute → monitor WATCH (CE side at T1)
# spot=24060 → PE side HOLD; CE T1_UP=24000 fires, CE T2_UP=24075 does not → WATCH
# ─────────────────────────────────────────────────────────────────────────────
h "F5: Full flow — IronCondor → confirm → execute → monitor WATCH (CE side at T1)"
$TRADE_IC = $T_IC_PEND   # T-204 PENDING IronCondor

info "Step 1: Confirm IronCondor (T-204) — 409 expected if S2 already confirmed it"
Show-Fields (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $TRADE_IC; action = 'CONFIRM' }) @('status')

info "Step 2: Execute (IronCondor — 4 legs, qty=2600 each)"
Show-Fields (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/execute" -Body @{
    tradeId = $TRADE_IC
    legs = @(
        @{ instrumentKey = 'NSE_FO|44617'; optionType = 'PE'; strike = 23900; action = 'SELL'; limitPrice = 38.15; quantity = 2600 }
        @{ instrumentKey = 'NSE_FO|44615'; optionType = 'PE'; strike = 23850; action = 'BUY';  limitPrice = 29.45; quantity = 2600 }
        @{ instrumentKey = 'NSE_FO|44635'; optionType = 'CE'; strike = 24150; action = 'SELL'; limitPrice = 78.10; quantity = 2600 }
        @{ instrumentKey = 'NSE_FO|44642'; optionType = 'CE'; strike = 24250; action = 'BUY';  limitPrice = 43.55; quantity = 2600 }
    )
}) @('executionStatus','slippageAlert')

info "Step 2.5: Build monitor-config (IronCondor — 4 fill price headers)"
Show-Fields (Invoke-Api -Url "$A2/api/v1/agent2/monitor-config/$TRADE_IC" -Headers @{
    'X-Short-Fill-Price'    = '38.15'
    'X-Long-Fill-Price'     = '29.45'
    'X-CE-Short-Fill-Price' = '78.10'
    'X-CE-Long-Fill-Price'  = '43.55'
}) @('actualNetPremiumPerUnit','slippageAlert','strategy')

info "Step 3: Evaluate IronCondor — spot=24060 (CE T1 zone: 24000 ≤ spot < 24075 → WATCH)"
Show-Fields (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$TRADE_IC" -Body @{
    niftySpot = 24060; vix = 20.5; shortLegLtp = 62.00; longLegLtp = 38.00; shortLegIv = 0.192 }) @('action','reason')

h "S5 Integration flows DONE"
