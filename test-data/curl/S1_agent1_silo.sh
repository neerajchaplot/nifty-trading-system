#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# S1 — Agent 1 silo tests
#
# Part A: Read pre-seeded signals from DB (no Upstox needed — works offline)
# Part B: Live /score endpoint examples (needs Upstox + NSE + Marketaux — market hours only)
#
# Pre-requisites: Run 02_seed_agent1_signals.sql first.
# ─────────────────────────────────────────────────────────────────────────────
source "$(dirname "$0")/vars.sh"

h "S1 — Agent 1 silo tests"

# ────────────────────────────────────────────────────────────────────────────
# PART A: Read pre-seeded signals
# Verify each signal was stored with correct bias/strength/confidence
# ────────────────────────────────────────────────────────────────────────────

h "A — Read pre-seeded signals via GET /latest"

info "S01: Bullish Extreme — expect bias=BULLISH, strength=EXTREME, confidence=HIGH"
curl -s "$A1/api/v1/agent1/signals/$SIG_BULL_EXT" | grep -E '"bias"|"strength"|"confidenceLabel"|"compositeScore"'

info "S02: Bullish Mild VIX High — expect bias=BULLISH, strength=MILD, confidence=MEDIUM"
curl -s "$A1/api/v1/agent1/signals/$SIG_BULL_MILD_VHIGH" | grep -E '"bias"|"strength"|"confidenceLabel"|"vixRegime"'

info "S04: Bullish Weak — expect bias=BULLISH, strength=WEAK, confidence=LOW"
curl -s "$A1/api/v1/agent1/signals/$SIG_BULL_WEAK" | grep -E '"bias"|"strength"|"confidenceLabel"'

info "S07: VIX Low (SKIP path) — expect vixRegime=LOW, confidence=MEDIUM"
curl -s "$A1/api/v1/agent1/signals/$SIG_SKIP_VLOW" | grep -E '"vixRegime"|"confidenceLabel"'

info "S08: VIX Extreme — expect vixRegime=EXTREME, confidence=LOW"
curl -s "$A1/api/v1/agent1/signals/$SIG_SKIP_VEXT" | grep -E '"vixRegime"|"confidenceLabel"|"compositeScore"'

info "S09: Bearish Mild — expect bias=BEARISH, strength=MILD"
curl -s "$A1/api/v1/agent1/signals/$SIG_BEAR_MILD" | grep -E '"bias"|"strength"|"confidenceLabel"'

info "S11: Data gaps — expect dataGaps non-empty, confidence=LOW"
curl -s "$A1/api/v1/agent1/signals/$SIG_GAPS" | grep -E '"dataGaps"|"confidenceLabel"'

info "S12: Commentary divergence — expect commentaryDivergence=true, confidence=LOW"
curl -s "$A1/api/v1/agent1/signals/$SIG_DIVERGE" | grep -E '"commentaryDivergence"|"confidenceLabel"'

info "S03: Bullish Mild VIX Normal — expect bias=BULLISH, strength=MILD, vixRegime=NORMAL, confidence=MEDIUM"
info "  Key: ivRegime=FAIR (not RICH). Strategy matrix: BullPutSpread also fires for VIX Normal IV Fair."
curl -s "$A1/api/v1/agent1/signals/$SIG_BULL_MILD_VNORM" | grep -E '"bias"|"strength"|"confidenceLabel"|"vixRegime"|"compositeScore"'
echo ""

info "S06: Neutral Weak VIX Normal — expect bias=NEUTRAL, strength=WEAK, vixRegime=NORMAL, confidence=MEDIUM"
info "  Key: Agent 2 picks ShortStraddle/Strangle when IV Rich. Score ≈ 0.03 (near flat tiers)."
curl -s "$A1/api/v1/agent1/signals/$SIG_NEUT_VNORM" | grep -E '"bias"|"strength"|"confidenceLabel"|"vixRegime"|"compositeScore"'
echo ""

info "S10: Bearish Extreme — expect bias=BEARISH, strength=EXTREME, vixRegime=HIGH, confidence=HIGH"
info "  Key: score < -0.50. Only signal with BEARISH EXTREME + HIGH confidence. Agent 2 → BearPutSpread (debit)."
curl -s "$A1/api/v1/agent1/signals/$SIG_BEAR_EXT" | grep -E '"bias"|"strength"|"confidenceLabel"|"compositeScore"|"vixRegime"'
echo ""

# ────────────────────────────────────────────────────────────────────────────
# PART B: Live /score examples
# ⚠ These call Upstox + NSE + Marketaux — only works during market hours.
#   Run manually on a weekday to verify end-to-end scoring.
# ────────────────────────────────────────────────────────────────────────────

h "B — Live /score examples (market hours only)"
echo "  ⚠  SKIP these on weekends — they call live APIs."
echo ""

info "B1: Bullish commentary + positive Marketaux sentiment"
echo "  Expected: Tier 4 scores +1 (bullish)"
cat << 'EOF'
curl -s -X POST "$A1/api/v1/agent1/score" \
  -H "Content-Type: application/json" \
  -d '{
    "commentary": "Nifty is in strong uptrend with FIIs turning net buyers. GST data and PMI both strong. Markets likely to continue rally to 25000 by expiry. Support at 24000, resistance at 24800.",
    "marketauxFetch": false
  }' | python3 -m json.tool
EOF

echo ""
info "B2: Bearish commentary (should score Tier 4 as -1)"
cat << 'EOF'
curl -s -X POST "$A1/api/v1/agent1/score" \
  -H "Content-Type: application/json" \
  -d '{
    "commentary": "Global risk-off due to Fed hawkishness. FIIs selling heavily in index futures. Nifty likely to test 23000 support. Suggest staying cautious, bears in control.",
    "marketauxFetch": false
  }' | python3 -m json.tool
EOF

echo ""
info "B3: Neutral commentary + live Marketaux fetch"
cat << 'EOF'
curl -s -X POST "$A1/api/v1/agent1/score" \
  -H "Content-Type: application/json" \
  -d '{
    "commentary": "Mixed signals from global markets. Nifty range-bound between 23800 and 24500. No clear trend directional bias.",
    "marketauxFetch": true
  }' | python3 -m json.tool
EOF

echo ""
info "B4: S03 pattern — mild bullish commentary targeting T4 = +1 (VIX Normal context)"
echo "  Drives Tier 4 = +1 (bullish). If live tiers 1–3 also mildly bullish → expect BULLISH MILD."
echo "  Compare with B1: B4 uses gentler language (not extreme rally). Same T4 direction, different conviction."
echo ""
echo "  B4a: marketauxFetch=false (no Marketaux quota used — pure commentary):"
cat << 'EOF'
curl -s -X POST "$A1/api/v1/agent1/score" \
  -H "Content-Type: application/json" \
  -d '{
    "commentary": "Nifty holding above its 20-EMA and 50-EMA. DII buying providing steady support on dips. GST collection numbers positive. Markets expected to inch higher towards 24500 this week with support at 23900. Bulls in control but pace is gradual. No major headwinds visible.",
    "marketauxFetch": false
  }' | python3 -m json.tool
EOF
echo ""
echo "  B4b: marketauxFetch=true (T4 = weighted avg of Marketaux ^NSEI sentiment + LLM extraction):"
cat << 'EOF'
curl -s -X POST "$A1/api/v1/agent1/score" \
  -H "Content-Type: application/json" \
  -d '{
    "commentary": "Nifty holding above 20-EMA and 50-EMA. DII buying steady. GST strong. Gradual upside to 24500 expected.",
    "marketauxFetch": true
  }' | python3 -m json.tool
EOF

echo ""
info "B5: S06 pattern — neutral/sideways commentary targeting T4 = 0 (Neutral Weak, IV Rich context)"
echo "  Drives Tier 4 = 0. If live tiers 1–3 also flat → expect NEUTRAL WEAK."
echo "  Agent 2 would pick ShortStraddle/Strangle if IV Rich, IronCondor if IV Rich + VIX High."
echo ""
echo "  B5a: marketauxFetch=false:"
cat << 'EOF'
curl -s -X POST "$A1/api/v1/agent1/score" \
  -H "Content-Type: application/json" \
  -d '{
    "commentary": "Nifty in consolidation between 23800 and 24400. FIIs showing no clear directional conviction — mixed between buying and selling. IV elevated due to upcoming event risk next week. Range-bound action likely until event outcome. No strong bias in either direction. Wait for breakout confirmation.",
    "marketauxFetch": false
  }' | python3 -m json.tool
EOF
echo ""
echo "  B5b: marketauxFetch=true (validates neutral news sentiment keeps T4 near zero):"
cat << 'EOF'
curl -s -X POST "$A1/api/v1/agent1/score" \
  -H "Content-Type: application/json" \
  -d '{
    "commentary": "Nifty consolidating between 23800–24400. No directional conviction. Range-bound until event clarity.",
    "marketauxFetch": true
  }' | python3 -m json.tool
EOF

echo ""
info "B6: S10 pattern — strong bearish commentary targeting T4 = -1 (Bearish Extreme context)"
echo "  Drives Tier 4 = -1. If live tiers 1–3 also strongly bearish → expect BEARISH EXTREME."
echo "  Agent 2 would pick BearPutSpread (debit). Gate 1 threshold is breakeven PoP ≥ 35% (not 80%)."
echo ""
echo "  B6a: marketauxFetch=false (most deterministic — isolates commentary impact on T4):"
cat << 'EOF'
curl -s -X POST "$A1/api/v1/agent1/score" \
  -H "Content-Type: application/json" \
  -d '{
    "commentary": "Nifty in sharp downtrend — all EMAs turning negative and golden cross reversing bearish. FII net selling accelerating in both index futures and options. Global risk-off driven by Fed hawkishness and weak China PMI data. Nifty likely to test 23000-22800 support zone this week. Avoid longs entirely. Bears firmly in control and selling on every bounce.",
    "marketauxFetch": false
  }' | python3 -m json.tool
EOF
echo ""
echo "  B6b: marketauxFetch=true (validates negative Marketaux ^NSEI sentiment amplifies T4 bearish):"
cat << 'EOF'
curl -s -X POST "$A1/api/v1/agent1/score" \
  -H "Content-Type: application/json" \
  -d '{
    "commentary": "Nifty in sharp downtrend. All EMAs negative. FII selling heavy in futures and options. Global risk-off. Target 23000.",
    "marketauxFetch": true
  }' | python3 -m json.tool
EOF

echo ""
h "S1 DONE"
