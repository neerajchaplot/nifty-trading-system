#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# vars.sh — shared variables for all test scripts
# Source this at the top of every script:  source ./vars.sh
# ─────────────────────────────────────────────────────────────────────────────

A1="http://localhost:8081"   # Agent 1 — market direction
A2="http://localhost:8082"   # Agent 2 — recommendation
A3="http://localhost:8083"   # Agent 3 — monitor
A5="http://localhost:8085"   # Agent 5 — execution

# ── Fixed test UUIDs (match the SQL seed files) ───────────────────────────────

# User profiles
UP_5L="00000001-0000-0000-0000-000000000001"
UP_10L="00000001-0000-0000-0000-000000000002"
UP_50L="00000001-0000-0000-0000-000000000003"
UP_1CR="00000001-0000-0000-0000-000000000004"

# Agent 1 signals
SIG_BULL_EXT="a1000001-0000-0000-0000-000000000001"       # S01 Bullish Extreme
SIG_BULL_MILD_VHIGH="a1000001-0000-0000-0000-000000000002" # S02 Bullish Mild VIX High
SIG_BULL_MILD_VNORM="a1000001-0000-0000-0000-000000000003" # S03 Bullish Mild VIX Normal
SIG_BULL_WEAK="a1000001-0000-0000-0000-000000000004"       # S04 Bullish Weak
SIG_NEUT_VHIGH="a1000001-0000-0000-0000-000000000005"      # S05 Neutral VIX High
SIG_NEUT_VNORM="a1000001-0000-0000-0000-000000000006"      # S06 Neutral VIX Normal
SIG_SKIP_VLOW="a1000001-0000-0000-0000-000000000007"       # S07 VIX Low → SKIP
SIG_SKIP_VEXT="a1000001-0000-0000-0000-000000000008"       # S08 VIX Extreme → SKIP
SIG_BEAR_MILD="a1000001-0000-0000-0000-000000000009"       # S09 Bearish Mild
SIG_BEAR_EXT="a1000001-0000-0000-0000-000000000010"        # S10 Bearish Extreme
SIG_GAPS="a1000001-0000-0000-0000-000000000011"            # S11 Data gaps
SIG_DIVERGE="a1000001-0000-0000-0000-000000000012"         # S12 Commentary divergence

# Agent 2 trades (PENDING_CONFIRM)
T_BPS_PEND="a2000001-0000-0000-0000-000000000001"     # BullPutSpread pending
T_BCS_PEND="a2000001-0000-0000-0000-000000000002"     # BullCallSpread pending
T_BCAS_PEND="a2000001-0000-0000-0000-000000000003"    # BearCallSpread pending
T_IC_PEND="a2000001-0000-0000-0000-000000000004"      # IronCondor pending
T_GATE_G1="a2000001-0000-0000-0000-000000000005"      # Rejected G1 failure
T_GATE_G4="a2000001-0000-0000-0000-000000000006"      # Rejected G4 failure
T_BPS_CONF="a2000001-0000-0000-0000-000000000007"     # BullPutSpread CONFIRMED
T_BCS_CONF="a2000001-0000-0000-0000-000000000008"     # BullCallSpread CONFIRMED
T_S03_PEND="a2000001-0000-0000-0000-000000000009"     # S03 BullPutSpread VIX Normal + IV Fair pending
T_S06_PEND="a2000001-0000-0000-0000-000000000010"     # S06 ShortStrangle VIX Normal + IV Rich pending
T_S10_PEND="a2000001-0000-0000-0000-000000000011"     # S10 BearPutSpread Bearish Extreme pending

# Agent 3 trades (ACTIVE)
T_A3_HOLD="a3000001-0000-0000-0000-000000000001"      # BullPutSpread → HOLD
T_A3_WATCH="a3000001-0000-0000-0000-000000000002"     # BullPutSpread → WATCH
T_A3_READJUST="a3000001-0000-0000-0000-000000000003"  # BullPutSpread → READJUST
T_A3_EXIT="a3000001-0000-0000-0000-000000000004"      # BullPutSpread → EXIT
T_A3_VIX="a3000001-0000-0000-0000-000000000005"       # VIX spike → EXIT
T_A3_PROFIT="a3000001-0000-0000-0000-000000000006"    # BullCallSpread → profit EXIT
T_A3_LOSSCUT="a3000001-0000-0000-0000-000000000007"   # BullCallSpread → loss EXIT

# Scheduler READJUST test (05_seed_readjust_scheduler_test.sql)
T_A3_IC_READJUST="a3000001-0000-0000-0000-000000000010"  # IronCondor → READJUST via scheduler

# ── Helper ────────────────────────────────────────────────────────────────────
h() {
    echo ""
    echo "════════════════════════════════════════"
    echo "  $*"
    echo "════════════════════════════════════════"
}

ok() { echo "  ✓ $*"; }
info() { echo "  → $*"; }
