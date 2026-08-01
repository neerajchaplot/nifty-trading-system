#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# capture_golden.sh — capture a REAL Phase-A output into a golden fixture (contract §5).
#
# A golden fixture is only trustworthy once it was produced by a real Agent1→2→5 entry.
# Run this AFTER such a run has left an ACTIVE trade (with monitor_config + entry_fills)
# in the DB, to snapshot that trade's handoff artifact into fixtures/golden/.
#
# Usage:
#   DB_URL='host=... dbname=... user=... sslmode=require' \
#     ./capture_golden.sh <tradeId> fixtures/golden/bull_put_10L.json
#
# Then run the contract test to lock the shape:
#   mvn -q -pl agent3-monitor test -Dtest=GoldenFixtureContractTest
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

TRADE="${1:?usage: capture_golden.sh <tradeId> <outfile.json>}"
OUT="${2:?usage: capture_golden.sh <tradeId> <outfile.json>}"
: "${DB_URL:?set DB_URL to a psql connection string}"
command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }

mkdir -p "$(dirname "$OUT")"

# -A -t → unaligned, tuples-only: raw JSON with no headers/padding.
psql "$DB_URL" -X -A -t -v tid="$TRADE" > "$OUT" <<'SQL'
SET search_path TO zupptrade_dev;
SELECT jsonb_pretty(jsonb_build_object(
    '_note',  'CAPTURED from a real Phase-A run on ' || now()::date || ' (trade ' || :'tid' || ')',
    '_meta',  jsonb_build_object('strategy',   strategy,
                                 'tradeCode',  trade_code,
                                 'status',     status,
                                 'capturedAt', now()),
    'monitor_config', monitor_config,
    'entry_fills',    entry_fills
))
FROM trades
WHERE id = :'tid'::uuid
  AND monitor_config IS NOT NULL;
SQL

if [ ! -s "$OUT" ]; then
    echo "✗ No trade with a monitor_config found for $TRADE." >&2
    echo "  Is it ACTIVE with fills confirmed (Agent 5 executed + monitor-config built)?" >&2
    rm -f "$OUT"
    exit 1
fi

echo "✓ Captured golden fixture → $OUT"
echo "  Lock the shape:  mvn -q -pl agent3-monitor test -Dtest=GoldenFixtureContractTest"
