#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Phase-B conductor (Increment 0) — walks a scenario timeline against Agent 3's
# EXISTING /evaluate endpoint using the override body it already accepts.
#
#   ZERO production impact — decision-only. It does NOT push to Agent 5 yet;
#   that arrives in Increment 1 (the sandbox-gated `act` seam). See README.md.
#
# Usage:  A3=http://localhost:8083 ./run_phase_b.sh scenarios/bull_put_walk.json
# Prereq: sql/seed_golden_active.sql loaded; Agent 3 running; jq + curl on PATH.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

A3="${A3:-http://localhost:8083}"
SCN="${1:?usage: run_phase_b.sh <scenario.json>}"
command -v jq   >/dev/null || { echo "ERROR: jq is required";   exit 2; }
command -v curl >/dev/null || { echo "ERROR: curl is required"; exit 2; }
[ -f "$SCN" ] || { echo "ERROR: scenario not found: $SCN"; exit 2; }

TRADE=$(jq -r '.tradeId' "$SCN")
NAME=$(jq -r '.name'    "$SCN")
N=$(jq '.ticks | length' "$SCN")

echo "==============================================================="
echo "  $NAME"
echo "  trade : $TRADE"
echo "  agent3: $A3   (decision-only — no Agent 5 push in Increment 0)"
echo "==============================================================="

pass=0; fail=0
for i in $(seq 0 $((N-1))); do
  tick=$(jq -c ".ticks[$i]" "$SCN")
  at=$(  echo "$tick" | jq -r '.at // "-"')
  exp=$( echo "$tick" | jq -r '.expectAction')
  spot=$(echo "$tick" | jq -r '.niftySpot')
  body=$(echo "$tick" | jq -c '{niftySpot, vix, shortLegLtp, longLegLtp, shortLegIv}')

  resp=$(curl -s -X POST "$A3/api/v1/agent3/evaluate/$TRADE" \
              -H 'Content-Type: application/json' -d "$body" || true)
  act=$( echo "$resp" | jq -r '.action // "ERROR"' 2>/dev/null || echo "ERROR")
  why=$( echo "$resp" | jq -r '.reason // ""'      2>/dev/null || echo "")

  if [ "$act" = "$exp" ]; then mark="PASS"; pass=$((pass+1)); else mark="FAIL"; fail=$((fail+1)); fi
  printf "  [%d] %-14s spot=%-6s  expect=%-9s got=%-9s  %s\n" "$i" "$at" "$spot" "$exp" "$act" "$mark"
  [ -n "$why" ] && printf "        reason: %s\n" "$why"
done

echo "---------------------------------------------------------------"
echo "  $pass passed, $fail failed"
echo "---------------------------------------------------------------"
[ "$fail" -eq 0 ]
