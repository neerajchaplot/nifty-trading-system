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
# Output files (all written to test-data/capture/snapshot/):
#   option_chain.json         — full option chain response from Upstox
#   spot_vix.json             — current Nifty spot and VIX
#   atm_strikes.json          — computed ATM CE/PE, 4–8 OTM strikes either side
#   fii_dii.json              — FII/DII data from NSE (previous day)
#   marketaux_sentiment.json  — latest news sentiment for ^NSEI
#   capture_meta.json         — timestamp, spot, expiry, DTE used for this capture
#
# After capture: update NSE_FO instrument keys in SQL seeds and curl scripts
#   grep -r "NSE_FO|REPLACE" ../sql/  → update those values with ATM/OTM keys from atm_strikes.json
# ─────────────────────────────────────────────────────────────────────────────

set -e

SNAPSHOT_DIR="$(dirname "$0")/snapshot"
mkdir -p "$SNAPSHOT_DIR"

EXPIRY_DATE="${EXPIRY_DATE:-2026-07-01}"
TOKEN="${UPSTOX_ACCESS_TOKEN:?UPSTOX_ACCESS_TOKEN is required}"
MX_KEY="${MARKETAUX_API_KEY:-}"

UPSTOX_BASE="https://api.upstox.com"
INSTRUMENT_KEY="NSE_INDEX%7CNifty%2050"
VIX_KEY="NSE_INDEX%7CIndia%20VIX"

echo "═══════════════════════════════════════════════════════════"
echo "  Nifty Weekend Test Data Capture"
echo "  Expiry date : $EXPIRY_DATE"
echo "  Output dir  : $SNAPSHOT_DIR"
echo "  Time        : $(date '+%Y-%m-%d %H:%M:%S IST')"
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

# Extract spot from option chain (use underlying_spot_price)
SPOT=$(echo "$OC_RESPONSE" | python3 -c "
import sys, json
d = json.load(sys.stdin)
chain = d.get('data', [])
if chain:
    print(chain[0].get('underlying_spot_price', 0))
" 2>/dev/null || echo "0")

if [ "$SPOT" = "0" ]; then
    echo "  ⚠  Could not extract spot from option chain. Check option_chain.json"
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

VIX=$(echo "$VIX_RESPONSE" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', {})
key = 'NSE_INDEX:India VIX'
print(data.get(key, {}).get('last_price', 0))
" 2>/dev/null || echo "0")

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
python3 - "$SNAPSHOT_DIR/option_chain.json" "$SNAPSHOT_DIR/atm_strikes.json" "$SPOT" << 'PYEOF'
import sys, json, math

oc_file, out_file, spot_str = sys.argv[1], sys.argv[2], sys.argv[3]
spot = float(spot_str) if spot_str != "0" else 0

with open(oc_file) as f:
    chain = json.load(f).get('data', [])

# Round to nearest 50
atm = round(spot / 50) * 50 if spot else 0
offsets = [-200, -150, -100, -50, 0, 50, 100, 150, 200]
target_strikes = {atm + o for o in offsets if (atm + o) > 0}

result = {"spot": spot, "atm": atm, "captured_at": "", "strikes": {}}

for row in chain:
    strike = row.get('strike_price')
    if strike not in target_strikes:
        continue
    label = f"{'+' if strike - atm >= 0 else ''}{int(strike - atm)}"
    ce = row.get('call_options', {}).get('market_data', {})
    pe = row.get('put_options', {}).get('market_data', {})
    ce_greeks = row.get('call_options', {}).get('option_greeks', {})
    pe_greeks = row.get('put_options', {}).get('option_greeks', {})
    result["strikes"][str(int(strike))] = {
        "label": f"ATM{label}" if label != "+0" else "ATM",
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

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: FII/DII data from NSE
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "→ Fetching FII/DII data from NSE ..."
NSE_FIIDATA_URL="https://www.nseindia.com/api/fiidiiTradeReact"

FII_RESPONSE=$(curl -s \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)" \
  -H "Accept: application/json" \
  -H "Referer: https://www.nseindia.com/" \
  "$NSE_FIIDATA_URL" 2>/dev/null || echo '{"error": "NSE fetch failed"}')

echo "$FII_RESPONSE" > "$SNAPSHOT_DIR/fii_dii.json"
echo "  ✓ fii_dii.json saved"
echo "  ⚠  If fii_dii.json shows an error, the NSE session cookie may have expired."
echo "     Download manually from NSE FII/DII page and save to snapshot/fii_dii.json"

# ─────────────────────────────────────────────────────────────────────────────
# Step 5: Marketaux sentiment (optional)
# ─────────────────────────────────────────────────────────────────────────────
echo ""
if [ -n "$MX_KEY" ]; then
    echo "→ Fetching Marketaux sentiment for ^NSEI ..."
    MX_RESPONSE=$(curl -s \
      "https://api.marketaux.com/v1/news/all?symbols=%5ENSEI&api_token=$MX_KEY&language=en&limit=3")
    echo "$MX_RESPONSE" > "$SNAPSHOT_DIR/marketaux_sentiment.json"

    # Extract average sentiment score
    AVG_SENTIMENT=$(echo "$MX_RESPONSE" | python3 -c "
import sys, json
d = json.load(sys.stdin)
scores = [e['sentiment_score'] for a in d.get('data',[]) for e in a.get('entities',[]) if e.get('symbol')=='NSEI']
if scores:
    print(round(sum(scores)/len(scores), 4))
else:
    print(0.0)
" 2>/dev/null || echo "0.0")

    echo "  ✓ marketaux_sentiment.json saved (avg_sentiment=$AVG_SENTIMENT)"
else
    echo "→ MARKETAUX_API_KEY not set — skipping Marketaux fetch"
    echo '{"data": [], "note": "not captured — set MARKETAUX_API_KEY and re-run"}' \
      > "$SNAPSHOT_DIR/marketaux_sentiment.json"
    AVG_SENTIMENT="0.0"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 6: Compute DTE and write capture metadata
# ─────────────────────────────────────────────────────────────────────────────
DTE=$(python3 -c "
from datetime import date
expiry = date.fromisoformat('$EXPIRY_DATE')
today = date.today()
print((expiry - today).days)
" 2>/dev/null || echo "3")

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
# Step 7: Print instrument key reference table
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  CAPTURE COMPLETE — Instrument Key Reference"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "Update these REPLACE_ placeholders in SQL seeds and curl scripts:"
echo ""
python3 - "$SNAPSHOT_DIR/atm_strikes.json" << 'PYEOF'
import sys, json
with open(sys.argv[1]) as f:
    data = json.load(f)
strikes = data.get('strikes', {})
print(f"  {'Strike':>8}  {'Label':<12}  {'CE instrumentKey':<30}  {'PE instrumentKey':<30}")
print(f"  {'-'*8}  {'-'*12}  {'-'*30}  {'-'*30}")
for k in sorted(strikes.keys(), key=lambda x: int(x)):
    v = strikes[k]
    ce_key = v['ce']['instrumentKey'] or 'MISSING'
    pe_key = v['pe']['instrumentKey'] or 'MISSING'
    print(f"  {k:>8}  {v['label']:<12}  {ce_key:<30}  {pe_key:<30}")
print("")
print("  ATM EM (from chain):")
atm = data.get('atm', 0)
atm_str = str(int(atm))
if atm_str in strikes:
    ce_ltp = strikes[atm_str]['ce']['ltp']
    pe_ltp = strikes[atm_str]['pe']['ltp']
    print(f"    ATM CE LTP={ce_ltp}  PE LTP={pe_ltp}  => Market EM={round(ce_ltp + pe_ltp, 2)}")
PYEOF

echo ""
echo "All files saved to: $SNAPSHOT_DIR"
echo ""
echo "Next steps:"
echo "  1. Update NSE_FO keys in test-data/sql/03_seed_agent2_trades.sql"
echo "  2. Update NSE_FO keys in test-data/curl/S4_agent5_silo.sh"
echo "  3. Update NSE_FO keys in test-data/curl/S5_integration_full_flow.sh"
echo "  4. Load SQL seeds: test-data/README.md for commands"
echo ""
