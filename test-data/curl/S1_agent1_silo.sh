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
h "S1 DONE"
