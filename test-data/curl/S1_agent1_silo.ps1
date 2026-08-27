# ─────────────────────────────────────────────────────────────────────────────
# S1 — Agent 1 silo tests (PowerShell)
#
# Part A: Read pre-seeded signals from DB (no Upstox needed — works offline)
# Part B: Live /score endpoint examples (needs Upstox + NSE + Marketaux — market hours only)
#
# Pre-requisites: Run 02_seed_agent1_signals.sql first.
# Run:  .\S1_agent1_silo.ps1
# ─────────────────────────────────────────────────────────────────────────────
. "$PSScriptRoot\vars.ps1"

h "S1 — Agent 1 silo tests"

# ─────────────────────────────────────────────────────────────────────────────
# PART A: Read pre-seeded signals
# Verify each signal was stored with correct bias/strength/confidence
# ─────────────────────────────────────────────────────────────────────────────

h "A — Read pre-seeded signals via GET /signals/{id}"

info "S01: Bullish Extreme — expect bias=BULLISH, strength=EXTREME, confidence=HIGH"
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_BULL_EXT") @('bias','strength','confidenceLabel','compositeScore')

info "S02: Bullish Mild VIX High — expect bias=BULLISH, strength=MILD, confidence=MEDIUM"
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_BULL_MILD_VHIGH") @('bias','strength','confidenceLabel','vixRegime')

info "S04: Bullish Weak — expect bias=BULLISH, strength=WEAK, confidence=LOW"
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_BULL_WEAK") @('bias','strength','confidenceLabel')

info "S07: VIX Low (SKIP path) — expect vixRegime=LOW, confidence=MEDIUM"
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_SKIP_VLOW") @('vixRegime','confidenceLabel')

info "S08: VIX Extreme — expect vixRegime=EXTREME, confidence=LOW"
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_SKIP_VEXT") @('vixRegime','confidenceLabel','compositeScore')

info "S09: Bearish Mild — expect bias=BEARISH, strength=MILD"
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_BEAR_MILD") @('bias','strength','confidenceLabel')

info "S11: Data gaps — expect dataGaps non-empty, confidence=LOW"
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_GAPS") @('dataGaps','confidenceLabel')

info "S12: Commentary divergence — expect commentaryDivergence=true, confidence=LOW"
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_DIVERGE") @('commentaryDivergence','confidenceLabel')

info "S03: Bullish Mild VIX Normal — expect bias=BULLISH, strength=MILD, vixRegime=NORMAL, confidence=MEDIUM"
info "  Key: ivRegime=FAIR (not RICH). Strategy matrix: BullPutSpread also fires for VIX Normal IV Fair."
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_BULL_MILD_VNORM") @('bias','strength','confidenceLabel','vixRegime','compositeScore')

info "S06: Neutral Weak VIX Normal — expect bias=NEUTRAL, strength=WEAK, vixRegime=NORMAL, confidence=MEDIUM"
info "  Key: Agent 2 picks ShortStraddle/Strangle when IV Rich. Score ≈ 0.03 (near flat tiers)."
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_NEUT_VNORM") @('bias','strength','confidenceLabel','vixRegime','compositeScore')

info "S10: Bearish Extreme — expect bias=BEARISH, strength=EXTREME, vixRegime=HIGH, confidence=HIGH"
info "  Key: score < -0.50. Only signal with BEARISH EXTREME + HIGH confidence. Agent 2 → BearPutSpread (debit)."
Show-Fields (Invoke-Api -Url "$A1/api/v1/agent1/signals/$SIG_BEAR_EXT") @('bias','strength','confidenceLabel','compositeScore','vixRegime')

# ─────────────────────────────────────────────────────────────────────────────
# PART B: Live /score examples
# ⚠ These call Upstox + NSE + Marketaux — only works during market hours.
#   Set $RunLive = $true to actually fire them; otherwise they are just printed.
# ─────────────────────────────────────────────────────────────────────────────

$RunLive = $false   # flip to $true on a weekday during market hours to actually call /score

h "B — Live /score examples (market hours only)"
Write-Host "  ⚠  SKIP these on weekends — they call live APIs. RunLive = $RunLive" -ForegroundColor Yellow

function Score-Example {
    param([string]$Label, [string]$Commentary, [bool]$MarketauxFetch)
    Write-Host ""
    info $Label
    $body = @{ commentary = $Commentary; marketauxFetch = $MarketauxFetch }
    if ($RunLive) {
        Show-Json (Invoke-Api -Method POST -Url "$A1/api/v1/agent1/score" -Body $body)
    }
    else {
        Write-Host "    (dry-run) Invoke-RestMethod POST $A1/api/v1/agent1/score" -ForegroundColor DarkGray
        Write-Host ("    body: " + ($body | ConvertTo-Json -Compress)) -ForegroundColor DarkGray
    }
}

Score-Example "B1: Bullish commentary + positive Marketaux sentiment (Tier 4 → +1)" `
    "Nifty is in strong uptrend with FIIs turning net buyers. GST data and PMI both strong. Markets likely to continue rally to 25000 by expiry. Support at 24000, resistance at 24800." $false

Score-Example "B2: Bearish commentary (Tier 4 → -1)" `
    "Global risk-off due to Fed hawkishness. FIIs selling heavily in index futures. Nifty likely to test 23000 support. Suggest staying cautious, bears in control." $false

Score-Example "B3: Neutral commentary + live Marketaux fetch" `
    "Mixed signals from global markets. Nifty range-bound between 23800 and 24500. No clear trend directional bias." $true

Score-Example "B4a: S03 pattern — mild bullish, no Marketaux quota (T4 → +1, gradual)" `
    "Nifty holding above its 20-EMA and 50-EMA. DII buying providing steady support on dips. GST collection numbers positive. Markets expected to inch higher towards 24500 this week with support at 23900. Bulls in control but pace is gradual. No major headwinds visible." $false

Score-Example "B4b: S03 pattern — with Marketaux fetch" `
    "Nifty holding above 20-EMA and 50-EMA. DII buying steady. GST strong. Gradual upside to 24500 expected." $true

Score-Example "B5a: S06 pattern — neutral/sideways (T4 → 0), no Marketaux" `
    "Nifty in consolidation between 23800 and 24400. FIIs showing no clear directional conviction — mixed between buying and selling. IV elevated due to upcoming event risk next week. Range-bound action likely until event outcome. No strong bias in either direction. Wait for breakout confirmation." $false

Score-Example "B5b: S06 pattern — with Marketaux fetch" `
    "Nifty consolidating between 23800–24400. No directional conviction. Range-bound until event clarity." $true

Score-Example "B6a: S10 pattern — strong bearish (T4 → -1), no Marketaux" `
    "Nifty in sharp downtrend — all EMAs turning negative and golden cross reversing bearish. FII net selling accelerating in both index futures and options. Global risk-off driven by Fed hawkishness and weak China PMI data. Nifty likely to test 23000-22800 support zone this week. Avoid longs entirely. Bears firmly in control and selling on every bounce." $false

Score-Example "B6b: S10 pattern — with Marketaux fetch" `
    "Nifty in sharp downtrend. All EMAs negative. FII selling heavy in futures and options. Global risk-off. Target 23000." $true

h "S1 DONE"
