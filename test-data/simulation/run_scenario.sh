#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Conductor — walks a scenario's virtual-clock timeline against Agent 3 (Stage 2).
#
# For each virtual tick inside market hours (09:15–15:30 IST, Mon–Fri) it:
#   POST /sim/clock/set {at}   → advance virtual time
#   POST /sim/run-cycle        → run ONE real monitoring cycle at that virtual moment
#
# Agent 3 (and Agent 5, for exits) must be running with:
#   -Dspring-boot.run.profiles=local,simulation
#   -Dspring-boot.run.arguments=--simulation.scenario=<name>
# and the trade being monitored must already be ACTIVE (Stage 2 seeds a golden trade).
#
# Usage: A3=http://localhost:8083 ./run_scenario.sh scenarios/bull_put_5day
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
A3="${A3:-http://localhost:8083}"
DIR="${1:?usage: run_scenario.sh <scenario-dir>}"
YAML="$DIR/scenario.yaml"
[ -f "$YAML" ] || { echo "scenario.yaml not found in $DIR" >&2; exit 2; }
PY_BIN="$(command -v python3 || command -v python || true)"
[ -n "$PY_BIN" ] || { echo "python3 (or python) is required" >&2; exit 2; }

"$PY_BIN" - "$A3" "$YAML" <<'PY'
import sys, json, re, urllib.request
from datetime import datetime, timedelta

a3, yaml_path = sys.argv[1], sys.argv[2]

def field(name):
    for line in open(yaml_path):
        m = re.match(r'\s*' + name + r':\s*(\S+)', line)
        if m:
            return m.group(1)
    return None

start = datetime.fromisoformat(field('start'))
end   = datetime.fromisoformat(field('end'))
step  = timedelta(minutes=int((field('step') or '5m').rstrip('m')))

MARKET_OPEN, MARKET_CLOSE = (9, 15), (15, 30)

def post(path, body):
    req = urllib.request.Request(a3 + path, data=json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

print(f"Walking {start.isoformat()} -> {end.isoformat()} step={step}")
t, ticks, ran = start, 0, 0
while t <= end:
    hm = (t.hour, t.minute)
    if t.weekday() < 5 and MARKET_OPEN <= hm <= MARKET_CLOSE:
        post('/sim/clock/set', {'at': t.isoformat()})
        post('/sim/run-cycle', {})
        ran += 1
    ticks += 1
    t += step
print(f"done: {ticks} ticks, {ran} market-hours cycles run")
PY
