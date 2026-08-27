# ─────────────────────────────────────────────────────────────────────────────
# Phase-B conductor (PowerShell) — walks a scenario timeline against Agent 3's
# EXISTING /evaluate endpoint using the override body it already accepts, and
# asserts expected-vs-actual action per tick.
#
#   Decision-only — it does NOT push to Agent 5. Zero production impact.
#
# Usage:
#   $env:A3 = "http://localhost:8083"   # optional, this is the default
#   .\run_phase_b.ps1 .\scenarios\bull_put_walk.json
#
# Prereq: sql\seed_golden_active.sql loaded; Agent 3 running.
# Exits 0 if every tick matches its expectAction, 1 otherwise.
# ─────────────────────────────────────────────────────────────────────────────
param(
    [Parameter(Mandatory = $true)][string]$Scenario,
    [string]$A3 = $(if ($env:A3) { $env:A3 } else { 'http://localhost:8083' })
)
if (-not (Test-Path $Scenario)) { Write-Error "scenario not found: $Scenario"; exit 2 }

$scn   = Get-Content $Scenario -Raw | ConvertFrom-Json
$trade = $scn.tradeId

Write-Host "==============================================================="
Write-Host ("  {0}" -f $scn.name)
Write-Host ("  trade : {0}" -f $trade)
Write-Host ("  agent3: {0}   (decision-only — no Agent 5 push)" -f $A3)
Write-Host "==============================================================="

$pass = 0; $fail = 0; $i = 0
foreach ($tick in $scn.ticks) {
    $exp  = $tick.expectAction
    $body = @{
        niftySpot   = $tick.niftySpot
        vix         = $tick.vix
        shortLegLtp = $tick.shortLegLtp
        longLegLtp  = $tick.longLegLtp
        shortLegIv  = $tick.shortLegIv
    } | ConvertTo-Json -Compress

    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$A3/api/v1/agent3/evaluate/$trade" `
            -Body $body -ContentType 'application/json' -TimeoutSec 30 -ErrorAction Stop
    } catch { $resp = $null }

    $act = if ($resp) { $resp.action } else { 'ERROR' }
    $why = if ($resp) { $resp.reason } else { '' }

    if ($act -eq $exp) { $mark = 'PASS'; $pass++; $color = 'Green' }
    else               { $mark = 'FAIL'; $fail++; $color = 'Red' }

    $at = if ($tick.at) { $tick.at } else { '-' }
    Write-Host ("  [{0}] {1,-14} spot={2,-6} expect={3,-9} got={4,-9} {5}" -f `
        $i, $at, $tick.niftySpot, $exp, $act, $mark) -ForegroundColor $color
    if ($why) { Write-Host ("        reason: {0}" -f $why) -ForegroundColor DarkGray }
    $i++
}

Write-Host "---------------------------------------------------------------"
Write-Host ("  {0} passed, {1} failed" -f $pass, $fail)
Write-Host "---------------------------------------------------------------"
if ($fail -eq 0) { exit 0 } else { exit 1 }
