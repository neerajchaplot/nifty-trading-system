# Weekend Test Guide — Nifty Trading System

This guide covers weekend testing with synthetic data when markets are closed.

---

## Overview

| Scope | What works offline | Needs Friday capture |
|---|---|---|
| Agent 1 — read signals | ✓ Pre-seeded signals (S1 Part A) | Live /score calls |
| Agent 2 — confirm/reject | ✓ Pre-seeded PENDING trades | /recommend (option chain) |
| Agent 3 — monitor/evaluate | ✓ Spot override body | — |
| Agent 5 — execute | ✓ simulate-fills mode | Real instrument keys |
| Integration E2E | ✓ Confirm → Execute → Monitor | /recommend step |

---

## Step 0: Pre-requisites

All services use **NeonDB** (`zupptrade_dev` schema):

```
Host:  ep-green-snow-aozeabar.c-2.ap-southeast-1.aws.neon.tech
DB:    neondb
User:  zupp_app
Schema: zupptrade_dev
```

Services run locally:

| Service | Port | Profile |
|---|---|---|
| Agent 1 | 8081 | `local` |
| Agent 2 | 8082 | `local` |
| Agent 3 | 8083 | `local` |
| Agent 5 | 8085 | `sandbox,local` |

Agent 5 **must** run with `sandbox` profile to enable simulate-fills:

```bash
# Agent 5 — sandbox profile
mvn spring-boot:run -pl agent5-execution \
  -Dspring-boot.run.profiles=sandbox,local
```

---

## Step 1: Friday — Capture Live Market Data

Run **before 3:30 PM on Friday** to capture option chain data:

```bash
export UPSTOX_ACCESS_TOKEN=your_token
export MARKETAUX_API_KEY=your_key
export EXPIRY_DATE=2026-07-01      # next Tuesday expiry

bash test-data/capture/capture_friday.sh
```

Output files saved to `test-data/capture/snapshot/`:

| File | Contents |
|---|---|
| `option_chain.json` | Full Upstox option chain response |
| `spot_vix.json` | Nifty spot + India VIX at capture time |
| `atm_strikes.json` | ATM ± 200 pts with instrument keys, LTP, IV, delta |
| `fii_dii.json` | NSE FII/DII trade data |
| `marketaux_sentiment.json` | Latest ^NSEI news sentiment |
| `capture_meta.json` | Capture timestamp, DTE, summary |

After capture, update instrument key placeholders in SQL seeds and curl scripts:

```bash
# Find all placeholders
grep -r "NSE_FO|REPLACE" test-data/sql/

# Then replace with real keys from atm_strikes.json
# Example replacement (use actual keys from the file):
#   NSE_FO|REPLACE_SELL_PE_23800  →  NSE_FO|71234
#   NSE_FO|REPLACE_BUY_PE_23700   →  NSE_FO|71235
```

---

## Step 2: Load SQL Seeds

Run in order. Each script is idempotent (uses `ON CONFLICT DO NOTHING`).

### Option A: psql (if available)

```bash
PGPASSWORD=your_password psql \
  "host=ep-green-snow-aozeabar.c-2.ap-southeast-1.aws.neon.tech \
   dbname=neondb user=zupp_app sslmode=require" \
  -f test-data/sql/01_seed_user_profiles.sql

# Repeat for 02, 03, 04 in order
```

### Option B: Any DB GUI (DBeaver, pgAdmin, TablePlus)

Connect with SSL to NeonDB, then execute each file in order:

1. `test-data/sql/01_seed_user_profiles.sql`
2. `test-data/sql/02_seed_agent1_signals.sql`
3. `test-data/sql/03_seed_agent2_trades.sql`
4. `test-data/sql/04_seed_agent3_active_trades.sql`

### Verify

```sql
-- Check row counts
SELECT 'user_profiles' AS tbl, COUNT(*) FROM zupptrade_dev.user_profiles
UNION ALL SELECT 'agent1_signals', COUNT(*) FROM zupptrade_dev.agent1_signals
UNION ALL SELECT 'trades', COUNT(*) FROM zupptrade_dev.trades;
-- Expected: user_profiles=4, agent1_signals=12, trades=15 (8 A2 + 7 A3)
```

---

## Step 3: Run Tests

Make scripts executable:

```bash
chmod +x test-data/curl/*.sh
```

### S1 — Agent 1 silo (offline)

```bash
bash test-data/curl/S1_agent1_silo.sh
```

Tests Part A only (reads pre-seeded signals). Part B is commented out (market hours).

**Expected:** Each signal returns correct bias/strength/confidence matching the seed values.

---

### S2 — Agent 2 silo (offline)

```bash
bash test-data/curl/S2_agent2_silo.sh
```

**Test matrix:**

| Test | Input | Expected |
|---|---|---|
| S2.1 | CONFIRM T-201 BullPutSpread | status=CONFIRMED, execution_order with 2 legs |
| S2.2 | CONFIRM T-202 BullCallSpread | status=CONFIRMED |
| S2.3 | CONFIRM T-203 BearCallSpread | status=CONFIRMED |
| S2.4 | CONFIRM T-204 IronCondor | status=CONFIRMED, 4 legs |
| S2.5 | REJECT T-201 | status=REJECTED |
| S2.6 | CONFIRM T-205 (already REJECTED) | 409 Conflict |
| S2.7 | CONFIRM T-203 with overrideLots=5 | lots=5 in execution_order |
| S2.8 | GET monitor-config T-207 | MonitorConfigDto with T1/T2/T3 thresholds |

> **Note:** S2.5 runs after S2.1 — T-201 will be CONFIRMED after S2.1. Either
> re-seed T-201 or run S2.5 before S2.1.

---

### S3 — Agent 3 silo (offline — uses spot override)

```bash
bash test-data/curl/S3_agent3_silo.sh
```

All S3 tests pass `{"niftySpot": ..., "vix": ...}` in the body to bypass Upstox.

**Test matrix:**

| Test | Trade | Spot | Expected action |
|---|---|---|---|
| S3.1 | T-301 BullPutSpread (short=23500) | 24200 | HOLD |
| S3.2 | T-302 BullPutSpread | 23650 (= short+150 = T1) | WATCH |
| S3.3 | T-303 BullPutSpread | 23575 (= short+75 = T2) | READJUST |
| S3.4 | T-304 BullPutSpread | 23450 (< short=23500 = T3) | EXIT |
| S3.5 | T-305 BullPutSpread | 24100 (fine), VIX=32 (+73%) | EXIT (VIX spike) |
| S3.6 | T-306 BullCallSpread | 24480 (> profit target) | EXIT (profit) |
| S3.7 | T-307 BullCallSpread | 23800 (MTM > 50% debit) | EXIT (loss cut) |

**Special setup for S3.5 (VIX spike):**

Agent 3 needs a previous VIX reading to compute the spike percentage.
Seed one row into `monitoring_evaluations` before running S3.5:

```sql
INSERT INTO zupptrade_dev.monitoring_evaluations
  (id, trade_id, evaluated_at, action, nifty_spot, india_vix, notes)
VALUES
  (gen_random_uuid(),
   'a3000001-0000-0000-0000-000000000005',
   NOW() - INTERVAL '5 minutes',
   'HOLD', 24100, 18.5, 'Prior reading for VIX spike test');
```

---

### S4 — Agent 5 silo (requires sandbox profile)

```bash
bash test-data/curl/S4_agent5_silo.sh
```

**Test matrix:**

| Test | Input | Expected |
|---|---|---|
| S4.1 | Execute BullPutSpread T-207 | ACTIVE, SIM-A2000007-L0/L1 order IDs, slippageAlert=false |
| S4.2 | Execute BullCallSpread T-208 | ACTIVE, actualNet negative (debit), slippageAlert=false |
| S4.3 | Execute with bad prices | slippageAlert=true, trade still ACTIVE |
| S4.4 | Non-existent tradeId | 404 Not Found |
| S4.5 | Execute already-ACTIVE trade | 409 Conflict |
| S4.6 | Exit BullPutSpread | CLOSED, exit fills recorded |

> **S4.3 setup:** Re-seed T-207 to CONFIRMED first (run S4.1, then reset):
> ```sql
> UPDATE zupptrade_dev.trades
>   SET status='CONFIRMED', entry_fills=NULL, confirmed_at=NOW()
>   WHERE id='a2000001-0000-0000-0000-000000000007';
> ```

---

### S5 — Integration flows (end-to-end)

```bash
bash test-data/curl/S5_integration_full_flow.sh
```

**Flow matrix:**

| Flow | Path | Expected final state |
|---|---|---|
| F1 | BullPutSpread → Execute → Monitor HOLD | Trade ACTIVE, action=HOLD |
| F2 | BullPutSpread → Execute → Monitor EXIT | Trade CLOSED, Agent 5 exit called |
| F3 | VIX Extreme signal → Recommend | Agent 2 returns SKIP, no trade |
| F4 | BearCallSpread → Confirm → Execute → Monitor HOLD | Trade ACTIVE, action=HOLD |
| F5 | IronCondor → Confirm → Execute → Monitor HOLD | Trade ACTIVE, 4-leg execute |

**F2 requires reset between F1 and F2:**

```sql
UPDATE zupptrade_dev.trades
  SET status='CONFIRMED', entry_fills=NULL, confirmed_at=NOW()
  WHERE id='a2000001-0000-0000-0000-000000000007';
```

---

## Step 4: Reset Test Data

To return all test data to initial state:

```sql
-- Run: test-data/sql/99_cleanup.sql
-- Then reload 01–04 in order
```

Or reset a single trade:

```sql
-- Reset a specific trade to PENDING_CONFIRM
UPDATE zupptrade_dev.trades
  SET status='PENDING_CONFIRM', entry_fills=NULL, confirmed_at=NULL
  WHERE id='<uuid>';
```

---

## Fixed UUIDs Quick Reference

These are defined in `test-data/curl/vars.sh`.

### User Profiles

| Variable | UUID | Capital |
|---|---|---|
| `UP_5L` | `00000001-...-0001` | Rs 5L |
| `UP_10L` | `00000001-...-0002` | Rs 10L |
| `UP_50L` | `00000001-...-0003` | Rs 50L |
| `UP_1CR` | `00000001-...-0004` | Rs 1Cr |

### Agent 1 Signals (expiry 2026-07-01)

| Variable | Signal | Bias/VIX |
|---|---|---|
| `SIG_BULL_EXT` | S01 | Bullish Extreme, VIX Normal |
| `SIG_BULL_MILD_VHIGH` | S02 | Bullish Mild, VIX High |
| `SIG_BULL_MILD_VNORM` | S03 | Bullish Mild, VIX Normal |
| `SIG_BULL_WEAK` | S04 | Bullish Weak |
| `SIG_NEUT_VHIGH` | S05 | Neutral, VIX High |
| `SIG_NEUT_VNORM` | S06 | Neutral, VIX Normal |
| `SIG_SKIP_VLOW` | S07 | VIX Low → SKIP |
| `SIG_SKIP_VEXT` | S08 | VIX Extreme → SKIP |
| `SIG_BEAR_MILD` | S09 | Bearish Mild |
| `SIG_BEAR_EXT` | S10 | Bearish Extreme |
| `SIG_GAPS` | S11 | Data gaps present |
| `SIG_DIVERGE` | S12 | Commentary divergence |

### Agent 2 Trades

| Variable | Trade | Strategy | Status |
|---|---|---|---|
| `T_BPS_PEND` | T-201 | BullPutSpread | PENDING_CONFIRM |
| `T_BCS_PEND` | T-202 | BullCallSpread | PENDING_CONFIRM |
| `T_BCAS_PEND` | T-203 | BearCallSpread | PENDING_CONFIRM |
| `T_IC_PEND` | T-204 | IronCondor | PENDING_CONFIRM |
| `T_GATE_G1` | T-205 | BullPutSpread | REJECTED (G1) |
| `T_GATE_G4` | T-206 | BullCallSpread | REJECTED (G4) |
| `T_BPS_CONF` | T-207 | BullPutSpread | CONFIRMED (ready for A5) |
| `T_BCS_CONF` | T-208 | BullCallSpread | CONFIRMED (ready for A5) |

### Agent 3 Active Trades

| Variable | Trade | Strategy | Expected action |
|---|---|---|---|
| `T_A3_HOLD` | T-301 | BullPutSpread | HOLD |
| `T_A3_WATCH` | T-302 | BullPutSpread | WATCH |
| `T_A3_READJUST` | T-303 | BullPutSpread | READJUST |
| `T_A3_EXIT` | T-304 | BullPutSpread | EXIT (price) |
| `T_A3_VIX` | T-305 | BullPutSpread | EXIT (VIX spike) |
| `T_A3_PROFIT` | T-306 | BullCallSpread | EXIT (profit) |
| `T_A3_LOSSCUT` | T-307 | BullCallSpread | EXIT (loss cut) |

---

## Troubleshooting

**`slippageAlert: true` on a clean debit spread execute**

Fixed in Agent 5 `TradeExecutionService.isSlippage()`. The fix uses `actual.signum()` (not `expected.signum()`) to detect credit vs debit direction. Verify Agent 5 is running latest code.

**Agent 3 evaluate fails with "Trade not found" or NPE on missing monitor_config**

Check that seed 04 loaded correctly. Agent 3 requires `monitor_config` JSONB to be non-null in the `trades` row.

**Agent 5 fails with "Trade is not in CONFIRMED status"**

Reset the trade to CONFIRMED (see SQL above). After execute the status becomes ACTIVE — you cannot execute twice without resetting.

**VIX spike test (S3.5) returns HOLD instead of EXIT**

The `monitoring_evaluations` table needs a prior row to compute the spike percentage. Run the INSERT above before S3.5.

**NSE FII/DII fetch returns empty or error**

NSE requires a browser session cookie. If automated fetch fails:
1. Open `https://www.nseindia.com/reports-indices-derivates-fii-dii-trade-react` in browser
2. Open DevTools → Network → find the `fiidiiTradeReact` request
3. Copy response and save to `test-data/capture/snapshot/fii_dii.json`

---

## Sandbox Safety Reminders

- `simulate-fills=true` and `bypass-margin-check=true` are **sandbox-only** flags.
  They must **never** appear in `application.yml` — only in `application-sandbox.yml`.
- SIM- order IDs (`SIM-XXXXXXXX-L0`) are synthetic. They will never appear in Upstox.
- All test UUIDs start with `00000001-`, `a1000001-`, `a2000001-`, `a3000001-` — easy to identify and clean up.
