# Simulation Phase Contract

> **Status:** design — pin this down *before* writing simulation code.
> **Purpose:** define the boundary between the two simulation phases so they can be
> authored, run, and asserted independently — without silently drifting apart.
> **Scope:** the *contract* only — phase boundaries, the handoff artifact, the two
> scenario schemas, and the two rules that keep the split safe. Harness mechanics
> (the `Clock` bean, `sim_clock`, `/sim/*` endpoints, Agent 5 fault modes) are
> governed separately and are out of scope here.

---

## 1. The phase model

The system already has a clean seam at the **trade object**. Agent 3 reads *only* the
`trades` row (`monitor_config` + `entry_fills`), never the signal or the recommendation
logic — which is why seeds `04`/`05` create ACTIVE trades without running Agent 1/2.

```
Phase A — ENTRY          market inputs ──Agent1 → Agent2 → Agent5(entry)──►  ACTIVE trade
                                                                            │
                                                            ┌───────────────┘  the handoff artifact
                                                            ▼
Phase B — MANAGEMENT     market path over time ──Agent3 → Agent5(exit/readjust)──►  CLOSED + alerts + monitoring rows
                                                                            │
                                                            ┌───────────────┘  DB state left behind
                                                            ▼
Phase C — REPORTING      Agent4 reads the DB ──►  analytics / signal-quality (assert, no push)
```

- **Phase A** and **Phase B** communicate through **one artifact**: the ACTIVE trade.
- **Phase C** consumes only what Phase B persisted; it is a read-only assertion phase.
- Dependency is one-directional (A → B → C) **except** the READJUST case — see §6.

Each phase has its **own scenario schema**. You never author one giant tape.

---

## 2. The handoff artifact (the A → B contract)

The *sole* contract between Phase A and Phase B is an ACTIVE `trades` row with these
three columns populated. Phase B must be able to run given **only** this — nothing else.

### 2.1 `status` invariants
- `status = 'ACTIVE'`
- `confirmed_at` set; `closed_at` null
- `agent1_signal_id` **must reference a real signal row** (required by Phase C — see §7)
- `expiry_date`, `dte` present and consistent with the virtual clock at entry

### 2.2 `monitor_config` (JSONB) — the shape Agent 3 reads

**2-leg credit / debit spread:**
```jsonc
{
  "tradeId": "<uuid>",
  "strategy": "BULL_PUT_SPREAD",           // or BEAR_CALL_SPREAD / BULL_CALL_SPREAD / BEAR_PUT_SPREAD
  "spreadDirection": "CREDIT",             // CREDIT | DEBIT
  "shortLeg": { "strike": 24000, "optionType": "PE", "action": "SELL", "ltp": 64.50, "instrumentKey": "NSE_FO|44621" },
  "longLeg":  { "strike": 23900, "optionType": "PE", "action": "BUY",  "ltp": 38.15, "instrumentKey": "NSE_FO|44617" },
  "actualNetPremiumPerUnit": 26.35,        // drives MTM — see §8
  "lots": 10, "lotSize": 65,
  "maxProfitTotal": 17128,
  "actualMaxLossTotal": 47873,
  "slippageAlert": false, "slippageAmount": 0,
  "thresholds": {
    "t1WatchNiftyLevel": 24150,            // credit spread: informational (Agent 3 uses live PoP)
    "t2ReadjustNiftyLevel": 24075,
    "t3ExitNiftyLevel": 24000,
    "t2LossThreshold": 11968,
    "t3LossThreshold": 23937
  },
  "expiryDate": "2026-07-07", "dte": 7
}
```

**Iron Condor** adds the second spread and uses **bilateral** thresholds (which Agent 3
treats as *hard* triggers, unlike the credit-spread case):
```jsonc
{
  "strategy": "IRON_CONDOR", "spreadDirection": "CREDIT",
  "shortLeg":  { "strike": 24000, "optionType": "PE", ... },   // PE spread
  "longLeg":   { "strike": 23900, "optionType": "PE", ... },
  "shortLeg2": { "strike": 24150, "optionType": "CE", ... },   // CE spread
  "longLeg2":  { "strike": 24250, "optionType": "CE", ... },
  "thresholds": {
    "t1WatchNiftyDown": 24050, "t2ReadjustNiftyDown": 23975, "t3ExitNiftyDown": 23900,
    "t1WatchNiftyUp":   24000, "t2ReadjustNiftyUp":   24075, "t3ExitNiftyUp":   24150,
    "t2LossThreshold": 1, "t3LossThreshold": 9999999
  }
}
```

### 2.3 `entry_fills` (JSONB) — one row per filled leg
```jsonc
[
  { "orderId": "SIM-<id>-L0", "instrumentKey": "NSE_FO|44621", "action": "SELL",
    "strike": 24000, "optionType": "PE", "quantityFilled": 650, "averageFillPrice": 64.50 },
  { "orderId": "SIM-<id>-L1", "instrumentKey": "NSE_FO|44617", "action": "BUY",
    "strike": 23900, "optionType": "PE", "quantityFilled": 650, "averageFillPrice": 38.15 }
]
```

> **Rule:** this schema is owned by Phase A's real output. Phase B fixtures must match it
> byte-for-byte in shape. Any field Agent 3 reads that Phase A does not emit is a contract
> break (this is the F2 "stale seed" failure mode — do not reintroduce it).

---

## 3. Scenario schema — Phase A (entry)

Drives Agent 1 → Agent 2 → Agent 5(entry). Produces and **asserts** the handoff artifact.

```yaml
phase: A
meta: { name: "...", scoreAt: 2026-07-13T09:20, expiry: 2026-07-14, lotSize: 65, userProfile: TEST_USER_10L }

market:                                # single point-in-time snapshot (entry is one moment)
  spot: 24050
  vix: 18.0
  indicators: { ema20: 23980, ema50: 23820, ema200: 23200, rsi14: 58, macd: bull, adx14: 26,
                higherHighs: true, higherLows: true }   # OR candles: [...] for TA4J to compute
  fiiDii: { fiiFutNet: 620, fiiLongRatio: 0.58, fiiOptNet: 200, diiNet: 450 }
  giftNifty: +40
  marketauxSentiment: 0.10
  commentary: "range with upward bias, support 23900"    # SimulatedCommentaryExtractor returns this
  optionChain:
    - { strike: 24000, type: PE, ltp: 64.5, iv: 0.19, delta: -0.20, pop: 0.82 }
    - { strike: 23900, type: PE, ltp: 38.1, iv: 0.20, delta: -0.17, pop: 0.84 }
    # CE side too for IC / debit

agent5Entry: { mode: FILL }            # FILL | SLIPPAGE | PARTIAL_ROLLBACK | TIMEOUT_MARKET | MARGIN_REJECT

expect:
  agent1: { bias: BULLISH, strength: MILD, confidenceLabel: MEDIUM }
  agent2: { strategy: BULL_PUT_SPREAD, gatesPass: true, lots: 10 }
  agent5: { status: ACTIVE, slippageAlert: false }
  handoff: { statusAfter: ACTIVE }     # asserts an ACTIVE trade exists → available to Phase B
```

Phase A **owns** `optionChain`, `indicators`, `fiiDii`, `commentary`. It does **not**
describe a timeline — entry is one moment.

---

## 4. Scenario schema — Phase B (management)

Drives Agent 3 → Agent 5(exit/readjust). Consumes an ACTIVE trade; asserts actions +
terminal state. This is a **timeline** of ticks. Much smaller than Phase A.

```yaml
phase: B
seedTrade:                             # EITHER a golden handoff fixture (§5) …
  from: "fixtures/golden/bull_put_10L.json"
  # … OR an inline hand-crafted monitor_config + entry_fills (must match §2 shape)

ticks:
  - at: 2026-07-13T13:00
    pop: 0.78                          # credit spread: target PoP → harness back-solves spot via inversePopSpot
    vix: 18.0
    expect: { action: WATCH, recorded: true }             # recorded = monitoring_evaluations row written

  - at: 2026-07-14T10:00
    spot: 23900                        # or give spot directly; legLtp for P&L-driven paths
    shortLegLtp: 120, longLegLtp: 78, shortLegIv: 0.22
    expect: { action: READJUST }
    readjust: STUB                     # STUB | CROSS_PHASE  — see §6

  - at: 2026-07-14T14:30
    vix: 32                            # VIX-extreme override
    expect: { action: EXIT }
    agent5Exit: { mode: PARTIAL_ROLLBACK }

expectTerminal:
  trade: { status: CLOSED, closeReason: "*VIX*" }
  ledger: [ TRADE_CLOSE_INITIATED, TRADE_CLOSED ]
  alerts: [ CRITICAL ]                 # CriticalAlertService rows, if the path raises them
  tradePnl: { present: true }          # required for Phase C — see §7
```

Phase B **owns** the spot/VIX/LTP timeline and the Agent-5 fill script. It does **not**
know or care how the trade was recommended.

---

## 5. Rule #1 — the golden-handoff rule (keeps A and B from drifting)

> A Phase-B `seedTrade` fixture is only trustworthy if it was **produced by a real Phase-A
> run at least once.**

- Maintain a `fixtures/golden/` set: each file is a `monitor_config` + `entry_fills`
  **captured from an actual Phase-A execution**, one per strategy
  (bull-put, bear-call, bull-call, bear-put, iron-condor, short-strangle).
- A **contract test** regenerates each golden fixture from Phase A and fails if the shape
  changes. This is the tripwire that would have caught the stale-gate-results problem.
- Hand-crafted Phase-B trades are allowed and encouraged — but only as **mutations of a
  golden fixture** (change spot triggers, premiums, DTE), never invented from scratch.

**Why:** without this anchor both phases can pass on data the real engine never produces.

---

## 6. Rule #2 — the READJUST decision (the one cross-phase coupling)

READJUST is the exception to "A and B don't touch": `ReadjustmentService` runs
exit → **fresh Agent 1 signal → Agent 2 recommend → confirm → Agent 5 execute**, i.e. it
calls *back into Phase A*. Every Phase-B tick that expects READJUST must declare a mode:

| Mode | Behaviour | Use for |
|---|---|---|
| `STUB` (default for Phase-B-only sims) | Re-entry calls to Agent 1/2 return a canned result (or are asserted as "attempted" and stopped). Old trade still exits and closes. | Testing the monitor **decision** and the exit push in isolation. |
| `CROSS_PHASE` | Real Agent 1/2/5 are wired; the full 6-step chain runs and a **new** ACTIVE trade is produced. | Testing the readjust **chain** end-to-end. Explicitly a combined A+B scenario. |

**Requirement:** a Phase-B-only harness must ship the `STUB` so it does not hang when a
tick hits READJUST with Agent 1/2 absent. A tick that omits `readjust:` on a READJUST
action is a scenario error.

---

## 7. Phase C (Agent 4) prerequisites — so reporting isn't hollow

Agent 4 is read-only over the DB Phase B leaves behind. Two things Phase B **must** persist
or Agent 4's numbers are empty/degenerate:

1. **`trade_pnl` rows** — Agent 4's P&L / win-rate read `trade_pnl`. **Decided:** which
   component writes it depends on *how the trade closed* — the sim models two distinct
   close paths:
   - **System-driven close** (Agent 3 `EXIT`, or the exit leg of a `READJUST`): the Agent 5
     **exit path writes the terminal `trade_pnl`** row, realised P&L = f(entry fills, exit fills).
   - **Expiry close** (held to Tuesday, no exit order — settles worthless/ITM): a
     **simulated positions-sync writes the settlement `trade_pnl`** row, realised P&L =
     f(entry fills, intrinsic value at settlement).

   Consequence: the harness must model **expiry settlement as its own close path** (not an
   exit order). Daily `trade_pnl` snapshots are optional for v1 — the terminal/settlement
   row is what Phase C asserts.
2. **Real `agent1_signal_id` linkage** — `/signal-quality` joins the trade back to its
   signal. A fabricated or null signal id makes that report meaningless. (This is why §2.1
   requires a real signal row even for hand-crafted Phase-B trades.)

Phase C scenarios are just: *run Agent 4 endpoints over the post-Phase-B DB and assert the
aggregates reconcile with the underlying `trade_pnl` / `trade_ledger` rows.*

---

## 8. Rule #3 — decisions vs. P&L magnitudes

Agent 3's MTM uses `monitor_config.actualNetPremiumPerUnit`.

- **Decision-ladder assertions** (does WATCH → READJUST → EXIT fire in the right order):
  a fabricated entry premium is fine. Drive by target PoP / spot.
- **Money assertions** (the realised P&L at exit was ₹X, the t2/t3 loss thresholds are
  correct): the entry premium and thresholds **must be self-consistent** with the legs —
  i.e. derived from a golden fixture, not an artificial value like `net=1.00`.

State which kind each Phase-B tick asserts. Do not assert money on an artificial-premium trade.

---

## 9. Contract invariants — checklist

- [ ] Phase B runs given **only** an ACTIVE trade's `monitor_config` + `entry_fills` (+ signal row).
- [ ] Every Phase-B `seedTrade` derives from a **golden fixture** validated by a contract test (§5).
- [ ] `monitor_config` shape matches §2 exactly; no field Agent 3 reads is Phase-A-absent.
- [ ] Every READJUST tick declares `STUB` or `CROSS_PHASE` (§6).
- [ ] Phase B persists `trade_pnl` and a real `agent1_signal_id` for Phase C (§7).
- [ ] Money assertions only on self-consistent (golden-derived) premiums (§8).
- [ ] Phases share no state beyond the trade row — no hidden coupling via seeds/UUIDs.

---

## 10. Assertion surface per phase

| Phase | Inputs | Asserts | DB tables checked |
|---|---|---|---|
| **A — entry** | market snapshot | signal bias/strength/conf; strategy + gate pass/fail; entry fills / slippage; ACTIVE trade produced | `agent1_signals`, `trades`, `trade_ledger`, `trade_executions` |
| **B — management** | market timeline + fill script | action per tick (HOLD/WATCH/READJUST/EXIT/PAUSE); recorded evaluation; terminal trade state; alerts | `monitoring_evaluations`, `trades`, `trade_ledger`, `notifications`/critical alerts |
| **C — reporting** | (post-B DB) | analytics reconcile; signal-quality buckets | `trade_pnl`, `trades`, `agent1_signals` (read-only) |

---

## 11. Decisions

1. **READJUST default** — ✅ **DECIDED: `STUB`** is the v1 default for the Phase-B-only
   harness; `CROSS_PHASE` is opt-in per tick (§6).
2. **`trade_pnl` in sim** — ✅ **DECIDED: two close paths** (§7) — system-driven exit writes
   `trade_pnl` from fills; expiry close writes it via a simulated positions-sync.
3. **Golden fixture coverage (v1)** — ✅ **DECIDED: bull-put credit + iron-condor** (they
   exercise both monitor mechanisms — live-PoP and stored-levels). Bear-call, bull-call,
   bear-put and short-strangle are added incrementally later.

---

**CONTRACT FROZEN.** All three decisions are settled; code can be written against §9. The
harness mechanics (Clock injection, sim_clock, /sim endpoints, Agent 5 fault modes) are
specified separately and must not weaken any invariant in §9. Changing any §1–§10 clause
requires re-opening this section.
