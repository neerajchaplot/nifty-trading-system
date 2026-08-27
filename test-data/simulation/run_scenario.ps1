# ─────────────────────────────────────────────────────────────────────────────
# Conductor (PowerShell) — walks a scenario's virtual-clock timeline against Agent 3.
#
# For each virtual tick inside market hours (09:15–15:30 IST, Mon–Fri) it:
#   POST /sim/clock/set {at}   → advance virtual time
#   POST /sim/run-cycle        → run ONE real monitoring cycle at that virtual moment
#
# Agent 3 (and Agent 5, for exits) must be running with:
#   SPRING_PROFILES_ACTIVE = local,simulation   and   SIMULATION_SCENARIO = <name>
# and the trade being monitored must already be ACTIVE (Phase A / a golden seed).
#
# Usage:
#   $env:A3 = "http://localhost:8083"   # optional, this is the default
#   .\run_scenario.ps1 .\scenarios\bull_put_5day
# ─────────────────────────────────────────────────────────────────────────────
param(
    [Parameter(Mandatory = $true)][string]$ScenarioDir,
    [string]$A3 = $(if ($env:A3) { $env:A3 } else { 'http://localhost:8083' })
)
$ErrorActionPreference = 'Stop'

$yaml = Join-Path $ScenarioDir 'scenario.yaml'
if (-not (Test-Path $yaml)) { Write-Error "scenario.yaml not found in $ScenarioDir"; exit 2 }

function Get-Field([string]$name) {
    foreach ($line in Get-Content $yaml) {
        if ($line -match ('^\s*' + [regex]::Escape($name) + ':\s*(\S+)')) { return $Matches[1] }
    }
    return $null
}

# Parse as DateTimeOffset so the +05:30 offset is preserved through to the POST body.
$start   = [datetimeoffset]::Parse((Get-Field 'start'))
$end     = [datetimeoffset]::Parse((Get-Field 'end'))
$stepRaw = Get-Field 'step'; if (-not $stepRaw) { $stepRaw = '5m' }
$step    = [timespan]::FromMinutes([int]($stepRaw -replace 'm$', ''))

$open  = 9 * 60 + 15    # 09:15
$close = 15 * 60 + 30   # 15:30

function Post-A3([string]$path, $body) {
    Invoke-RestMethod -Method Post -Uri ($A3 + $path) `
        -Body ($body | ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 30 | Out-Null
}

Write-Host ("Walking {0} -> {1} step={2}" -f $start.ToString('o'), $end.ToString('o'), $step)

$t = $start; $ticks = 0; $ran = 0
while ($t -le $end) {
    $isWeekday = ($t.DayOfWeek -ne [DayOfWeek]::Saturday) -and ($t.DayOfWeek -ne [DayOfWeek]::Sunday)
    $hm = $t.Hour * 60 + $t.Minute
    if ($isWeekday -and $hm -ge $open -and $hm -le $close) {
        Post-A3 '/sim/clock/set' @{ at = $t.ToString("yyyy-MM-ddTHH:mm:sszzz") }
        Post-A3 '/sim/run-cycle' @{}
        $ran++
    }
    $ticks++
    $t = $t.Add($step)
}
Write-Host ("done: {0} ticks, {1} market-hours cycles run" -f $ticks, $ran)
