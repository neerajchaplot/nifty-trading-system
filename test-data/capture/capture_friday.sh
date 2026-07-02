#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# capture_friday.sh — Capture live Upstox market data for weekend replay
#
# Run this on FRIDAY before 3:30 PM (market close).
# Saves option chain, spot, VIX, and ATM strikes to test-data/capture/snapshot/
#
# Usage:
#   export UPSTOX_ACCESS_TOKEN=your_token
#   export MARKETAUX_API_KEY=your_key      # optional — for sentiment capture
#   export EXPIRY_DATE=2026-07-01          # next Tuesday expiry
#   bash capture_friday.sh
#
# Requirements: curl (Git Bash includes it), Python 3 (python / python3 / py)
#   Python is used for JSON parsing. Install from python.org if not present.
#
# Output files (all written to test-data/capture/snapshot/):
#   option_chain.json         — full option chain response from Upstox
#   spot_vix.json             — current Nifty spot and VIX
#   atm_strikes.json          — computed ATM CE/PE, 4–8 OTM strikes either side
#   fii_dii.json              — FII/DII data from NSE (previous day)
#   marketaux_sentiment.json  — latest news sentiment for ^NSEI
#   capture_meta.json         — timestamp, spot, expiry, DTE used for this capture
# ─────────────────────────────────────────────────────────────────────────────

set -e

SNAPSHOT_DIR="$(dirname "$0")/snapshot"
mkdir -p "$SNAPSHOT_DIR"

EXPIRY_DATE="${EXPIRY_DATE:-2026-07-01}"
TOKEN="${UPSTOX_ACCESS_TOKEN:?UPSTOX_ACCESS_TOKEN is required}"
MX_KEY="${MARKETAUX_API_KEY:-}"

UPSTOX_BASE="https://api.upstox.com"
VIX_KEY="NSE_INDEX%7CIndia%20VIX"

# ─────────────────────────────────────────────────────────────────────────────
# Detect Python — tries python3, python, py in that order.
# On Windows the 'python3' entry-point is often a Microsoft Store stub that
# opens the Store instead of running. We verify by actually executing a no-op.
# ─────────────────────────────────────────────────────────────────────────────
detect_python() {
    for cmd in python3 python py; do
        if command -v "$cmd" &>/dev/null; then
            if "$cmd" -c "import sys; sys.exit(0)" 2>/dev/null; then
                echo "$cmd"
                return 0
            fi
        fi
    done
    return 1
}

PYTHON=""
if PYTHON=$(detect_python); then
    echo "  ✓ Python detected : $PYTHON ($($PYTHON --version 2>&1))"
else
    echo "  ⚠  Python not found. Install from https://www.python.org/downloads/"
    echo "     JSON extraction steps will be skipped — raw JSON files will still be saved."
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  Nifty Weekend Test Data Capture"
echo "  Expiry date : $EXPIRY_DATE"
echo "  Output dir  : $SNAPSHOT_DIR"
echo "  Time        : $(date '+%Y-%m-%d %H:%M:%S')"
echo "═══════════════════════════════════════════════════════════"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Option chain
# ─────────────────────────────────────────────────────────────────────────────
echo "→ Fetching Nifty option chain for expiry $EXPIRY_DATE ..."
OC_RESPONSE=$(curl -s \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  "$UPSTOX_BASE/v2/option/chain?instrument_key=NSE_INDEX%7CNifty%2050&expiry_date=$EXPIRY_DATE")

echo "$OC_RESPONSE" > "$SNAPSHOT_DIR/option_chain.json"
echo "  ✓ option_chain.json saved"

# Extract spot from option chain (use underlying_spot_price from first row)
SPOT="0"
if [ -n "$PYTHON" ]; then
    SPOT=$(echo "$OC_RESPONSE" | $PYTHON -c "
import sys, json
d = json.load(sys.stdin)
chain = d.get('data', [])
if chain:
    print(chain[0].get('underlying_spot_price', 0))
else:
    print(0)
" 2>/dev/null || echo "0")
fi

if [ "$SPOT" = "0" ] || [ "$SPOT" = "0.0" ]; then
    echo "  ⚠  Could not extract spot. Check option_chain.json for the raw response."
fi
echo "  ✓ Spot (from chain) : $SPOT"

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: VIX (via Upstox LTP quote)
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "→ Fetching India VIX ..."
VIX_RESPONSE=$(curl -s \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  "$UPSTOX_BASE/v2/market-quote/ltp?instrument_key=$VIX_KEY")

VIX="0"
if [ -n "$PYTHON" ]; then
    VIX=$(echo "$VIX_RESPONSE" | $PYTHON -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', {})
key = 'NSE_INDEX:India VIX'
print(data.get(key, {}).get('last_price', 0))
" 2>/dev/null || echo "0")
fi

echo "{\"nifty_spot\": $SPOT, \"india_vix\": $VIX, \"captured_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" \
  > "$SNAPSHOT_DIR/spot_vix.json"
echo "  ✓ spot_vix.json saved (spot=$SPOT, vix=$VIX)"

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: Extract ATM and OTM strikes from option chain
# Saves strike details (instrumentKey, ltp, iv, delta) for:
#   ATM CE/PE, and strikes at ±50, ±100, ±150, ±200 pts from spot
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "→ Extracting ATM and OTM strikes ..."

if [ -n "$PYTHON" ] && [ "$SPOT" != "0" ] && [ "$SPOT" != "0.0" ]; then
    $PYTHON - "$SNAPSHOT_DIR/option_chain.json" "$SNAPSHOT_DIR/atm_strikes.json" "$SPOT" << 'PYEOF'
import sys, json

oc_file, out_file, spot_str = sys.argv[1], sys.argv[2], sys.argv[3]
spot = float(spot_str)

with open(oc_file) as f:
    chain = json.load(f).get('data', [])

# Round to nearest 50
atm = round(spot / 50) * 50
offsets = [-200, -150, -100, -50, 0, 50, 100, 150, 200]
target_strikes = {atm + o for o in offsets if (atm + o) > 0}

result = {"spot": spot, "atm": atm, "captures_at": "", "strikes": {}}

for row in chain:
    strike = row.get('strike_price')
    if strike not in target_strikes:
        continue
    offset = int(strike - atm)
    label = ("ATM" if offset == 0
             else f"ATM+{offset}" if offset > 0
             else f"ATM{offset}")
    ce = row.get('call_options', {}).get('market_data', {})
    pe = row.get('put_options', {}).get('market_data', {})
    ce_greeks = row.get('call_options', {}).get('option_greeks', {})
    pe_greeks = row.get('put_options', {}).get('option_greeks', {})
    result["strikes"][str(int(strike))] = {
        "label": label,
        "ce": {
            "instrumentKey": row.get('call_options', {}).get('instrument_key', ''),
            "ltp": ce.get('ltp', 0),
            "iv": ce_greeks.get('iv', 0),
            "delta": ce_greeks.get('delta', 0),
            "theta": ce_greeks.get('theta', 0),
            "vega": ce_greeks.get('vega', 0),
            "oi": ce.get('oi', 0)
        },
        "pe": {
            "instrumentKey": row.get('put_options', {}).get('instrument_key', ''),
            "ltp": pe.get('ltp', 0),
            "iv": pe_greeks.get('iv', 0),
            "delta": pe_greeks.get('delta', 0),
            "theta": pe_greeks.get('theta', 0),
            "vega": pe_greeks.get('vega', 0),
            "oi": pe.get('oi', 0)
        }
    }

with open(out_file, 'w') as f:
    json.dump(result, f, indent=2)

print(f"  ✓ Extracted {len(result['strikes'])} strike levels around ATM={atm}")
PYEOF
    echo "  ✓ atm_strikes.json saved"
else
    echo "  ⚠  Skipped — Python not available or spot=0. atm_strikes.json not created."
    echo '{"spot": 0, "atm": 0, "strikes": {}, "note": "not extracted — run with Python installed"}' \
      > "$SNAPSHOT_DIR/atm_strikes.json"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: FII/DII data from Upstox (same token — no NSE dependency)
#   GET /v2/market/fii?data_type=NSE_FO|INDEX_FUTURES  — fii net futures + long ratio
#   GET /v2/market/fii?data_type=NSE_FO|INDEX_OPTIONS  — fii net options
#   GET /v2/market/dii?data_type=NSE_EQ|CASH           — dii net cash
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "→ Fetching FII/DII data from Upstox ..."
FROM_DATE=$(date -d "7 days ago" +%Y-%m-%d 2>/dev/null || date -v-7d +%Y-%m-%d 2>/dev/null || echo "")
[ -z "$FROM_DATE" ] && FROM_DATE=$(date +%Y-%m-%d)   # fallback: use today, Upstox returns last available

FII_FUTURES=$(curl -s \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  "$UPSTOX_BASE/v2/market/fii?data_type=NSE_FO%7CINDEX_FUTURES&interval=1D&from=$FROM_DATE")

FII_OPTIONS=$(curl -s \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  "$UPSTOX_BASE/v2/market/fii?data_type=NSE_FO%7CINDEX_OPTIONS&interval=1D&from=$FROM_DATE")

DII_CASH=$(curl -s \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  "$UPSTOX_BASE/v2/market/dii?data_type=NSE_EQ%7CCASH&interval=1D&from=$FROM_DATE")

# Combine into one file matching what UpstoxFiiDiiClient produces
cat > "$SNAPSHOT_DIR/fii_dii.json" << EOF
{
  "capturedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "fromDate": "$FROM_DATE",
  "fii_futures": $FII_FUTURES,
  "fii_options": $FII_OPTIONS,
  "dii_cash":    $DII_CASH
}
EOF
echo "  ✓ fii_dii.json saved (fii_futures + fii_options + dii_cash)"

# ─────────────────────────────────────────────────────────────────────────────
# Step 5: Marketaux sentiment (optional)
# ─────────────────────────────────────────────────────────────────────────────
echo ""
AVG_SENTIMENT="0.0"
if [ -n "$MX_KEY" ]; then
    echo "→ Fetching Marketaux sentiment for ^NSEI ..."
    MX_RESPONSE=$(curl -s \
      "https://api.marketaux.com/v1/news/all?symbols=%5ENSEI&api_token=$MX_KEY&language=en&limit=3")
    echo "$MX_RESPONSE" > "$SNAPSHOT_DIR/marketaux_sentiment.json"

    if [ -n "$PYTHON" ]; then
        AVG_SENTIMENT=$(echo "$MX_RESPONSE" | $PYTHON -c "
import sys, json
d = json.load(sys.stdin)
scores = [e['sentiment_score'] for a in d.get('data',[])
          for e in a.get('entities',[]) if e.get('symbol')=='NSEI']
print(round(sum(scores)/len(scores), 4) if scores else 0.0)
" 2>/dev/null || echo "0.0")
    fi
    echo "  ✓ marketaux_sentiment.json saved (avg_sentiment=$AVG_SENTIMENT)"
else
    echo "→ MARKETAUX_API_KEY not set — skipping Marketaux fetch"
    echo '{"data": [], "note": "not captured — set MARKETAUX_API_KEY and re-run"}' \
      > "$SNAPSHOT_DIR/marketaux_sentiment.json"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 6: Compute DTE — Python preferred; pure-bash fallback via date arithmetic
# ─────────────────────────────────────────────────────────────────────────────
DTE="3"
if [ -n "$PYTHON" ]; then
    DTE=$($PYTHON -c "
from datetime import date
expiry = date.fromisoformat('$EXPIRY_DATE')
print(max(0, (expiry - date.today()).days))
" 2>/dev/null || echo "3")
else
    # Bash fallback — GNU date (available in Git Bash on Windows)
    EXPIRY_EPOCH=$(date -d "$EXPIRY_DATE" +%s 2>/dev/null || echo "0")
    TODAY_EPOCH=$(date +%s)
    if [ "$EXPIRY_EPOCH" != "0" ]; then
        DTE=$(( (EXPIRY_EPOCH - TODAY_EPOCH) / 86400 ))
        [ "$DTE" -lt 0 ] && DTE=0
    fi
fi
echo "  ✓ DTE = $DTE days to $EXPIRY_DATE"

# ─────────────────────────────────────────────────────────────────────────────
# Step 7: Write capture metadata
# ─────────────────────────────────────────────────────────────────────────────
cat > "$SNAPSHOT_DIR/capture_meta.json" << EOF
{
  "capturedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "capturedBy": "capture_friday.sh",
  "expiryDate": "$EXPIRY_DATE",
  "dte": $DTE,
  "niftySpot": $SPOT,
  "indiaVix": $VIX,
  "avgMarketauxSentiment": $AVG_SENTIMENT,
  "files": [
    "option_chain.json",
    "spot_vix.json",
    "atm_strikes.json",
    "fii_dii.json",
    "marketaux_sentiment.json"
  ],
  "notes": [
    "Update NSE_FO instrument keys in SQL seeds before loading:",
    "  grep -r 'NSE_FO|REPLACE' ../sql/ to find all placeholders",
    "  Replace with instrumentKey values from atm_strikes.json"
  ]
}
EOF
echo "  ✓ capture_meta.json saved"

# ─────────────────────────────────────────────────────────────────────────────
# Step 8: Print instrument key reference table
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  CAPTURE COMPLETE — Instrument Key Reference"
echo "═══════════════════════════════════════════════════════════"
echo ""

if [ -n "$PYTHON" ] && [ -f "$SNAPSHOT_DIR/atm_strikes.json" ]; then
    echo "Update these REPLACE_ placeholders in SQL seeds and curl scripts:"
    echo ""
    $PYTHON - "$SNAPSHOT_DIR/atm_strikes.json" << 'PYEOF'
import sys, json
with open(sys.argv[1]) as f:
    data = json.load(f)
strikes = data.get('strikes', {})
if not strikes:
    print("  No strikes extracted — see atm_strikes.json")
    sys.exit(0)
print(f"  {'Strike':>8}  {'Label':<10}  {'CE instrumentKey':<25}  {'CE LTP':>8}  {'PE instrumentKey':<25}  {'PE LTP':>8}")
print(f"  {'-'*8}  {'-'*10}  {'-'*25}  {'-'*8}  {'-'*25}  {'-'*8}")
for k in sorted(strikes.keys(), key=lambda x: int(x)):
    v = strikes[k]
    ce_key = v['ce']['instrumentKey'] or 'MISSING'
    pe_key = v['pe']['instrumentKey'] or 'MISSING'
    print(f"  {k:>8}  {v['label']:<10}  {ce_key:<25}  {v['ce']['ltp']:>8.2f}  {pe_key:<25}  {v['pe']['ltp']:>8.2f}")
print("")
atm = data.get('atm', 0)
atm_str = str(int(atm))
if atm_str in strikes:
    ce_ltp = strikes[atm_str]['ce']['ltp']
    pe_ltp = strikes[atm_str]['pe']['ltp']
    print(f"  Market EM (ATM CE + PE): {ce_ltp} + {pe_ltp} = {round(ce_ltp + pe_ltp, 2)} pts")
PYEOF
else
    echo "  ⚠  Cannot print table — Python not available or atm_strikes.json missing."
    echo "     Open atm_strikes.json manually to find the instrument keys."
fi

echo ""
echo "All files saved to: $SNAPSHOT_DIR"
echo ""
echo "Next steps:"
echo "  1. Update NSE_FO keys in test-data/sql/03_seed_agent2_trades.sql"
echo "  2. Update NSE_FO keys in test-data/curl/S4_agent5_silo.sh"
echo "  3. Update NSE_FO keys in test-data/curl/S5_integration_full_flow.sh"
echo "  4. Load SQL seeds: see test-data/README.md"
echo ""
