# ─────────────────────────────────────────────────────────────────────────────
# S3 — Agent 3 silo tests (PowerShell)
#
# All tests use the niftySpot override body — works OFFLINE (no Upstox needed).
# Pre-requisites: Run 04_seed_agent3_active_trades.sql first.
#
# CreditSpreadMonitorStrategy uses PoP-based decisions (not Nifty level thresholds):
#   PoP ≥ 80% → HOLD    PoP 75–79% → WATCH
#   PoP 65–74% → READJUST    PoP < 65% → EXIT
#   VIX > 24 → PAUSE   Spot ≤ short strike (PE) → T3_SHORT_STRIKE_BREACH → EXIT
#
# IV overrides calibrated for DTE=4 (test date 2026-07-03, expiry 2026-07-07):
#   HOLD     : spot=24450, σ=0.185 → PoP≈83.8%
#   WATCH    : spot=24350, σ=0.192 → PoP≈77.2%
#   READJUST : spot=24200, σ=0.200 → PoP≈66.3%
#   EXIT     : spot=23950 → breach detection (σ irrelevant)
#
# BullCallSpread (T-306, T-307): DebitSpreadMonitorStrategy
#   shortLeg=SELL CE 24250, longLeg=BUY CE 24100, entryNetDebit=58.90
#   t1WatchNiftyLevel=24200 (→ WATCH), t2ReadjustNiftyLevel=24250 (→ EXIT profit)
#   t2LossThreshold=3829 (total Rs loss stop = 50% of debit paid)
# Run:  .\S3_agent3_silo.ps1
# ─────────────────────────────────────────────────────────────────────────────
. "$PSScriptRoot\vars.ps1"

h "S3 — Agent 3 silo tests (offline mode)"

# S3.1 — HOLD
h "S3.1 — Expected action: HOLD (spot 24450, σ=0.185, PoP≈83.8%)"
Show-Json (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$T_A3_HOLD" -Body @{
    niftySpot = 24450; vix = 17.5; shortLegLtp = 15.00; longLegLtp = 6.00; shortLegIv = 0.185 })

# S3.2 — WATCH
h "S3.2 — Expected action: WATCH (spot 24350, σ=0.192, PoP≈77.2%)"
Show-Json (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$T_A3_WATCH" -Body @{
    niftySpot = 24350; vix = 18.2; shortLegLtp = 28.00; longLegLtp = 14.00; shortLegIv = 0.192 })

# S3.3 — READJUST
h "S3.3 — Expected action: READJUST (spot 24200, σ=0.200, PoP≈66.3%)"
Show-Json (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$T_A3_READJUST" -Body @{
    niftySpot = 24200; vix = 19.1; shortLegLtp = 55.00; longLegLtp = 35.00; shortLegIv = 0.200 })

# S3.4 — EXIT (short strike breach)
h "S3.4 — Expected action: EXIT (spot 23950, breached short strike 24000)"
Show-Json (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$T_A3_EXIT" -Body @{
    niftySpot = 23950; vix = 19.8; shortLegLtp = 148.00; longLegLtp = 105.00; shortLegIv = 0.238 })

# S3.5 — PAUSE (VIX Extreme override)
h "S3.5 — Expected action: PAUSE (VIX=32.0 Extreme, spot 24450 is fine but VIX overrides)"
info "VIX > 24 triggers PAUSE (auto-trading suspended, manual review required)."
info "VIX Extreme check fires FIRST before any PoP/price logic."
Show-Json (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$T_A3_VIX" -Body @{
    niftySpot = 24450; vix = 32.0; shortLegLtp = 15.00; longLegLtp = 6.00; shortLegIv = 0.185 })

# S3.6 — EXIT (profit): BullCallSpread reaches T2 target
h "S3.6 — Expected action: EXIT profit (BullCallSpread, spot 24250 > T1=24200)"
Show-Json (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$T_A3_PROFIT" -Body @{
    niftySpot = 24250; vix = 14.8; shortLegLtp = 55.00; longLegLtp = 160.00; shortLegIv = 0.162 })

# S3.7 — EXIT (loss cut): BullCallSpread MTM loss > 50% of premium paid
h "S3.7 — Expected action: EXIT loss cut (BullCallSpread, MTM loss Rs 6617 > threshold Rs 3829)"
info "spot=24000 → both CE 24100 and CE 24250 deeply OTM with DTE=4."
info "shortLegLtp=2.00 (SELL CE 24250), longLegLtp=10.00 (BUY CE 24100)"
info "currentNetPremium = 10-2 = 8. MTM = (8 - 58.90) × 2 × 65 = -6617 ≤ -3829 → EXIT"
Show-Json (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$T_A3_LOSSCUT" -Body @{
    niftySpot = 24000; vix = 16.2; shortLegLtp = 2.00; longLegLtp = 10.00; shortLegIv = 0.175 })

# S3.8 — Error path: evaluate CLOSED trade (manual SQL required)
h "S3.8 — Error: evaluate CLOSED trade (expect 409 Conflict)"
info "Set a trade to CLOSED first:"
info "UPDATE zupptrade_dev.trades SET status='CLOSED' WHERE id='$T_A3_HOLD';  (revert after test)"
info "(commented out — see test-data/TODO.md for a pre-seeded CLOSED trade T-308)"
# Show-Json (Invoke-Api -Method POST -Url "$A3/api/v1/agent3/evaluate/$T_A3_HOLD" -Body @{ niftySpot = 24300; vix = 17.5 })

h "S3 DONE"
