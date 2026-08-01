# How to run & verify the simulator — all four agents

No building. This is the run-and-verify guide for what exists today, across Agent 1, 2, 3, 5.

---

## ⚠️ Read first

All four agents point at the **same NeonDB**. Running them in the `simulation` profile writes
**simulated** signals and trades into the **same tables as your real data** (`agent1_signals`,
`trades`, `trade_pnl`). They're real rows, just generated from a folder. Know how you'll tell them
apart / clean them up (e.g. note the `tradeId`s you create, delete after). A separate sim schema is
a future improvement — not done yet.

---

## The model: one folder, two clocks

Every agent starts the **same way** and reads the **same scenario folder**
(`test-data/simulation/scenarios/bull_put_5day`). The only difference is *when* they read it:

- **Agent 1, 2, 5** → **fixed** clock at `entry.at` (they act once, at entry)
- **Agent 3** → **ticking** clock you advance across the 5 days (monitoring over time)

---

## Step 1 — Start each agent (one PowerShell per module)

In each module's PowerShell, set the profile + scenario as env vars, then run. Spring's relaxed
binding maps `SIMULATION_SCENARIO` → `simulation.scenario`, so no messy `-D` quoting:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local,simulation"
$env:SIMULATION_SCENARIO   = "bull_put_5day"
mvn spring-boot:run
```

Run that in **each** of these module folders (separate terminals, as you already do):

| Agent | Module folder | Port |
|-------|---------------|------|
| Agent 1 | `agent1-market_analyst` | 8081 |
| Agent 2 | `agent2-recommendation` | 8082 |
| Agent 3 | `agent3-monitor` | 8083 |
| Agent 5 | `agent5-execution` | *(see its startup log: "Tomcat started on port …")* |

**Verify startup (each terminal):** look for a loud line like
`SIMULATION (agentX) scenario 'bull_put_5day' loaded from …` and, for 1/2, a fixed-clock line.
If you see `--simulation.scenario was not provided` → the env var didn't take; re-check it's set in
*that* terminal before `mvn`.

> The scenario folder is found relative to where you run `mvn`: from the module folder it resolves
> `../test-data/simulation/scenarios/bull_put_5day`. If not found, the app fails fast with the path
> it tried — run from the module folder (as you do) and it works.

---

## Step 2 — Get the user profile UUID (once)

Agent 2 needs `userProfileId`. The seed row is `user_id='default'`. Get its UUID from NeonDB:

```sql
SELECT id FROM user_profiles WHERE user_id = 'default';
```

Call that value `<UP>` below. (If `agent-user` is running, `GET /api/v1/agent-user/me` returns it as
`id` too.)

---

## Step 3 — PHASE A: entry flow (fixed clock, run once)

No `X-API-Key` header — security is off in `local`. Run these in any shell (Git Bash `curl` shown).

**3.1 Agent 1 — score from the folder:**
```bash
curl -s -X POST http://localhost:8081/api/v1/agent1/score \
  -H "Content-Type: application/json" -d '{}'
```
✅ **Verify:** response has `bias`, `strength`, `compositeScore`, and an `id`. That `id` = `<SIG>`.
The Agent 1 terminal logs `SIMULATION (agent1) inputs @ … spot=… vix=… pcr=…` — those numbers must
match the folder's `spot_vix.csv` / `option_chain.csv` at the entry date. **This is the proof Agent 1
is reading the folder, not Upstox.**

**3.2 Agent 2 — recommend a trade from the folder chain:**
```bash
curl -s -X POST http://localhost:8082/api/v1/agent2/recommend \
  -H "Content-Type: application/json" \
  -d '{"agent1SignalId":"<SIG>","userProfileId":"<UP>"}'
```
✅ **Verify:** response has `tradeId` (= `<TRADE>`), a `strategy`, and `shortLeg`/`longLeg` with
strikes near the folder's spot. If `status:REJECTED` with a `skipReason` (e.g. VIX/among gates),
that's a *valid* outcome — the scenario just didn't qualify; tweak the folder and re-score.

**3.3 Agent 2 — confirm:**
```bash
curl -s -X POST http://localhost:8082/api/v1/agent2/confirm \
  -H "Content-Type: application/json" \
  -d '{"tradeId":"<TRADE>","action":"CONFIRM"}'
```
✅ **Verify:** `status:CONFIRMED`. Note the `shortLeg`/`longLeg` `instrumentKey`, `strike`,
`optionType`, `ltp`, and the `lots` + `lotSize` — you need them for the next step.

**3.4 Agent 5 — execute (simulated fills):**
Build the legs from 3.3. `quantity = lots × lotSize`; `limitPrice = leg ltp`.
```bash
curl -s -X POST http://localhost:<agent5>/api/v1/agent5/execute \
  -H "Content-Type: application/json" \
  -d '{
    "tradeId":"<TRADE>",
    "legs":[
      {"instrumentKey":"<shortLeg.instrumentKey>","optionType":"PE","strike":<s>,"action":"SELL","limitPrice":<shortLtp>,"quantity":<lots*lotSize>},
      {"instrumentKey":"<longLeg.instrumentKey>","optionType":"PE","strike":<l>,"action":"BUY","limitPrice":<longLtp>,"quantity":<lots*lotSize>}
    ]
  }'
```
✅ **Verify:** `executionStatus` filled, `slippageAlert`. In NeonDB, `trades.status` for `<TRADE>`
is now `ACTIVE`.

**3.5 Seed the monitor config (so Agent 3 can watch it):**
```bash
curl -s "http://localhost:8082/api/v1/agent2/monitor-config/<TRADE>" \
  -H "X-Short-Fill-Price: <shortLtp>" -H "X-Long-Fill-Price: <longLtp>"
```
✅ **Verify:** response includes `thresholds` (T1/T2/T3 Nifty levels).

---

## Step 4 — PHASE B: monitoring walk (ticking clock, 5 days in minutes)

Agent 3 now walks the folder's timeline. The conductor script does the clock-advance + run-cycle loop:

```bash
A3=http://localhost:8083 ./test-data/simulation/run_scenario.sh test-data/simulation/scenarios/bull_put_5day
```
It advances Agent 3's virtual clock every 5 sim-minutes across market hours and runs one real
monitoring cycle each tick.

✅ **Verify:** Agent 3's terminal logs each cycle reading the folder's **falling** spot. As spot
drops through the trade's T1 → T2 → T3 levels, you see `WATCH` → `READJUST` → `EXIT`. On `EXIT`,
Agent 3 calls Agent 5, and in NeonDB `trades.status` for `<TRADE>` becomes `CLOSED` with a
`close_reason` and a `trade_pnl` row.

To drive it by hand instead of the script:
```bash
curl -s -X POST http://localhost:8083/sim/clock/set -H "Content-Type: application/json" -d '{"at":"2026-07-10T10:00:00+05:30"}'
curl -s -X POST http://localhost:8083/sim/run-cycle -H "Content-Type: application/json" -d '{}'
```

---

## What "it works" looks like end-to-end

1. Agent 1 log shows folder spot/vix/pcr → returns a signal `id`.
2. Agent 2 returns a `tradeId` with strikes off the folder chain.
3. `trades.status` → `ACTIVE` after execute.
4. Walking the clock flips the trade `ACTIVE` → `CLOSED` with a P&L, driven only by the folder's
   falling spot.

If all four happen, the foundation is real.

---

## Known gaps (today)

- **Phase A steps 3.1–3.5 are manual curls** — no one-command conductor yet. (Building that is the
  only remaining code item; it just automates these same calls.)
- `candles.csv` has 16 rows; the 200-EMA needs ~200. Until then Agent 1 scores that one EMA signal
  neutral — not a blocker, just a slightly muted Tier 1A.
- Simulated rows land in the real NeonDB tables (see the warning up top).
