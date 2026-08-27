# ─────────────────────────────────────────────────────────────────────────────
# S2 — Agent 2 silo tests (PowerShell)
#
# Tests: confirm, reject, lot override, monitor-config. Reads from pre-seeded trades.
# Pre-requisites: Run 03_seed_agent2_trades.sql first.
#
# NOTE: /agent2/recommend requires live Upstox option chain — tested separately
#       via capture/replay in S5_integration_full_flow.ps1
# Run:  .\S2_agent2_silo.ps1
# ─────────────────────────────────────────────────────────────────────────────
. "$PSScriptRoot\vars.ps1"

h "S2 — Agent 2 silo tests"

# ─────────────────────────────────────────────────────────────────────────────
# S2.1–S2.4 — Confirm each PENDING_CONFIRM strategy
# ─────────────────────────────────────────────────────────────────────────────

h "S2.1 — CONFIRM: BullPutSpread"
info "Trade: T-201 (Bullish Mild VIX High, 10L capital) — expect status=CONFIRMED, 2 legs"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_BPS_PEND; action = 'CONFIRM' })

h "S2.2 — CONFIRM: BullCallSpread"
info "Trade: T-202 (Bullish Extreme, 10L capital)"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_BCS_PEND; action = 'CONFIRM' })

h "S2.3 — CONFIRM: BearCallSpread"
info "Trade: T-203 (Bearish Mild VIX High, 10L capital)"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_BCAS_PEND; action = 'CONFIRM' })

h "S2.4 — CONFIRM: IronCondor"
info "Trade: T-204 (Neutral VIX High, 50L capital)"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_IC_PEND; action = 'CONFIRM' })

# ─────────────────────────────────────────────────────────────────────────────
# S2.5 — REJECT a trade
# ─────────────────────────────────────────────────────────────────────────────

h "S2.5 — REJECT: BullPutSpread (user rejects)"
info "Trade: T-201 (re-seed before running — it was confirmed above). Expected: status=REJECTED"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_BPS_PEND; action = 'REJECT' })

# ─────────────────────────────────────────────────────────────────────────────
# S2.6 — Confirm already-REJECTED trade (error path)
# ─────────────────────────────────────────────────────────────────────────────

h "S2.6 — Attempt to confirm a REJECTED trade (error expected)"
info "Trade: T-205 (G1 gate failure — already REJECTED). Expected: 409 Conflict or 404 Not Found"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_GATE_G1; action = 'CONFIRM' })

# ─────────────────────────────────────────────────────────────────────────────
# S2.7 — Confirm with lot override
# ─────────────────────────────────────────────────────────────────────────────

h "S2.7 — CONFIRM with overrideLots=5 (user reduces position size)"
info "Trade: T-203 BearCallSpread (re-seed first). Expected: lots in execution_order = 5, not 8"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_BCAS_PEND; action = 'CONFIRM'; overrideLots = 5 })

# ─────────────────────────────────────────────────────────────────────────────
# S2.8 — monitor-config endpoint
# ─────────────────────────────────────────────────────────────────────────────

h "S2.8 — GET monitor-config (called by Agent 3 after execution)"
info "Trade: T-207 (pre-confirmed BullPutSpread with entry_fills)"
info "Fill prices: SELL PE 24000 @ 64.50, BUY PE 23900 @ 38.15"
info "Expected: MonitorConfigDto with strategy=BULL_PUT_SPREAD, thresholds, slippageAlert=false"
Show-Json (Invoke-Api -Url "$A2/api/v1/agent2/monitor-config/$T_BPS_CONF" -Headers @{ 'X-Short-Fill-Price' = '64.50'; 'X-Long-Fill-Price' = '38.15' })

# ─────────────────────────────────────────────────────────────────────────────
# S2.9 – S2.11: Confirm the three new signal scenarios
# Pre-requisite: 03_seed_agent2_trades.sql re-loaded (it now includes T-209/210/211)
# ─────────────────────────────────────────────────────────────────────────────

h "S2.9 — CONFIRM: BullPutSpread from S03 (Bullish Mild + VIX Normal + IV Fair)"
info "Trade: T-209 (signal: S03, 10L user)"
info "Differentiator vs T-201: ivRegime=FAIR — tests BullPutSpread also fires for VIX Normal IV Fair"
info "Expected: status=CONFIRMED, execution_order with SELL PE 24000 + BUY PE 23900"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_S03_PEND; action = 'CONFIRM' })

h "S2.10 — CONFIRM: ShortStrangle from S06 (Neutral Weak + VIX Normal + IV Rich)"
info "Trade: T-210 (signal: S06, 50L user — larger capital needed for naked position margin)"
info "Key assertions:"
info "  • strategy = SHORT_STRANGLE"
info "  • 2 SELL legs only (SELL PE 23950 + SELL CE 24150) — no long legs"
info "  • lots = 3 (margin-constrained — not lot-sized like a spread)"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_S06_PEND; action = 'CONFIRM' })

h "S2.11 — CONFIRM: BearPutSpread from S10 (Bearish Extreme + VIX High)"
info "Trade: T-211 (signal: S10, 10L user)"
info "Key assertions:"
info "  • strategy = BEAR_PUT_SPREAD (debit)"
info "  • BUY leg is HIGH strike PE (23650), SELL leg is LOW strike PE (23550) — opposite of BullPutSpread"
info "  • Gate 1 threshold = 35% (debit breakeven PoP), not 80% (credit PoP)"
info "  • Instrument keys: NSE_FO|44595 (PE 23650) / NSE_FO|44591 (PE 23550) from 2026-07-04 capture"
Show-Json (Invoke-Api -Method POST -Url "$A2/api/v1/agent2/confirm" -Body @{ tradeId = $T_S10_PEND; action = 'CONFIRM' })

h "S2 DONE"
