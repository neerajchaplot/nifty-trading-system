# ─────────────────────────────────────────────────────────────────────────────
# S4 — Agent 5 silo tests (PowerShell)
#
# Agent 5 must be running with -Dspring.profiles.active=sandbox,local
# (bypass-margin-check=true, simulate-fills=true)
#
# Pre-requisites: Run 03_seed_agent2_trades.sql first.
#
# Instrument keys from 2026-07-04 capture (expiry 2026-07-07, ATM=24250):
#   NSE_FO|44621 = PE 24000  LTP=15.65   (short leg BullPutSpread)
#   NSE_FO|44617 = PE 23900  LTP=9.30    (long  leg BullPutSpread)
#   NSE_FO|44633 = CE 24100  LTP=209.55  (long  leg BullCallSpread)
#   NSE_FO|44642 = CE 24250  LTP=102.15  (short leg BullCallSpread)
# Run:  .\S4_agent5_silo.ps1
# ─────────────────────────────────────────────────────────────────────────────
. "$PSScriptRoot\vars.ps1"

h "S4 — Agent 5 silo tests (sandbox + simulate-fills)"

# S4.1 — Happy path: BullPutSpread execute
h "S4.1 — Execute BullPutSpread (happy path)"
info "Trade: $T_BPS_CONF | lotSize=65 | lots=8 → quantity=520"
Show-Json (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/execute" -Body @{
    tradeId = $T_BPS_CONF
    legs = @(
        @{ instrumentKey = 'NSE_FO|44621'; optionType = 'PE'; strike = 24000; action = 'SELL'; limitPrice = 64.50; quantity = 520 }
        @{ instrumentKey = 'NSE_FO|44617'; optionType = 'PE'; strike = 23900; action = 'BUY';  limitPrice = 38.15; quantity = 520 }
    )
})

# S4.2 — Happy path: BullCallSpread execute (debit — verify no false slippage alert)
h "S4.2 — Execute BullCallSpread (debit spread — verify no false slippage alert)"
info "Trade: $T_BCS_CONF | lots=2 → quantity=130. Expected: actualNet negative (debit=-58.90), slippageAlert=false"
Show-Json (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/execute" -Body @{
    tradeId = $T_BCS_CONF
    legs = @(
        @{ instrumentKey = 'NSE_FO|44633'; optionType = 'CE'; strike = 24100; action = 'BUY';  limitPrice = 102.45; quantity = 130 }
        @{ instrumentKey = 'NSE_FO|44642'; optionType = 'CE'; strike = 24250; action = 'SELL'; limitPrice = 43.55;  quantity = 130 }
    )
})

# S4.3 — Slippage alert: actual net worse than expected × 0.90
h "S4.3 — Slippage alert (actualNet 12.00 vs expectedNet 26.35, threshold 23.72)"
info "Re-seed T-207 to CONFIRMED before running:"
info "UPDATE zupptrade_dev.trades SET status='CONFIRMED' WHERE id='$T_BPS_CONF';"
Show-Json (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/execute" -Body @{
    tradeId = $T_BPS_CONF
    legs = @(
        @{ instrumentKey = 'NSE_FO|44621'; optionType = 'PE'; strike = 24000; action = 'SELL'; limitPrice = 56.00; quantity = 520 }
        @{ instrumentKey = 'NSE_FO|44617'; optionType = 'PE'; strike = 23900; action = 'BUY';  limitPrice = 44.00; quantity = 520 }
    )
})

# S4.4 — Error path: non-existent tradeId
h "S4.4 — Error: unknown tradeId (expect 404 / 'Trade not found')"
Show-Json (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/execute" -Body @{
    tradeId = '99999999-9999-9999-9999-999999999999'
    legs = @(
        @{ instrumentKey = 'NSE_FO|DUMMY'; optionType = 'CE'; strike = 24000; action = 'BUY'; limitPrice = 100.00; quantity = 65 }
    )
})

# S4.5 — Error path: trade already ACTIVE (double execute)
h "S4.5 — Error: attempt to execute already-ACTIVE trade"
info "(T_BPS_CONF is ACTIVE after S4.1 — do NOT re-seed before this test)"
Show-Json (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/execute" -Body @{
    tradeId = $T_BPS_CONF
    legs = @(
        @{ instrumentKey = 'NSE_FO|44621'; optionType = 'PE'; strike = 24000; action = 'SELL'; limitPrice = 64.50; quantity = 520 }
        @{ instrumentKey = 'NSE_FO|44617'; optionType = 'PE'; strike = 23900; action = 'BUY';  limitPrice = 38.15; quantity = 520 }
    )
})

# S4.6 — Exit flow: close the BullPutSpread opened in S4.1
h "S4.6 — Exit BullPutSpread (reverse legs, market order)"
info "Run AFTER S4.1 (trade must be ACTIVE). Expected: status=CLOSED, exit fills with SIM-X- order IDs"
Show-Json (Invoke-Api -Method POST -Url "$A5/api/v1/agent5/exit/$T_BPS_CONF" -Body @{
    tradeId = $T_BPS_CONF
    reason = 'MANUAL_TEST_EXIT'
    exitLegs = @(
        @{ instrumentKey = 'NSE_FO|44621'; originalAction = 'SELL'; quantity = 520 }
        @{ instrumentKey = 'NSE_FO|44617'; originalAction = 'BUY';  quantity = 520 }
    )
})

h "S4 DONE"
