# Implementation Status — Cold-Start Briefing

> Read CLAUDE.md first (the spec), then this file (current state).
> This file tells you what's been built, what changed from spec, and what's next.
> Update this file at the end of every session.

---

## Module Map (actual on disk)

```
nifty-trading-system/
├── shared-domain/          ← DTOs, enums, constants — COMPLETE
├── core-module/            ← AlertService, UpstoxClient, auto-config — COMPLETE
├── ledger-module/          ← TradeLedgerService (event sourcing) — COMPLETE
├── agent1-market_analyst/  ← Market direction scoring — COMPLETE
├── agent2-recommendation/  ← Trade recommendation — COMPLETE
├── agent3-monitor/         ← Monitoring + ReadjustmentService — COMPLETE
├── agent5-execution/       ← Upstox order execution — MOSTLY COMPLETE
├── agent4-backtest/        ← Trade Analytics & Audit — COMPLETE (read-only, port 8084)
├── upstox-auth/            ← Daily token refresh — STATE UNKNOWN (not reviewed)
├── ui-design/              ← HTML wireframe (source of truth for UI design)
└── zupptrade-ui/           ← Angular 18 UI app — COMPLETE (see below)
```

**Base package:** `com.the3Cgrp.zupptrade`
**Module packages:** `com.the3Cgrp.zupptrade.agent1`, `.agent2`, `.agent3`, `.agent5`

---

## Flyway Migrations (V1–V9)

| File | Content |
|---|---|
| V1__init.sql | Core tables: agent1_signals, user_profiles, trades, reference_data, scoring_audit_log |
| V2__seed_user_profile.sql | Default user profile seed data |
| V3__add_fii_dii_snapshots.sql | fii_dii_snapshots table (daily FII/DII data + trend) |
| V4__alter_trades_add_trade_code.sql | trade_code column on trades (format: TRD-YYYYMMDD-NNNN) |
| V5__create_trade_ledger.sql | trade_ledger event-sourcing table |
| V6__create_trade_executions.sql | trade_executions table (per-leg fill records) |
| V7__create_trade_pnl.sql | trade_pnl table |
| V8__create_monitoring_evaluations.sql | monitoring_evaluations table (Agent 3 cycle results) |
| V9__create_notifications_shedlock.sql | notifications + shedlock tables |
| V112__create_critical_alerts.sql | critical_alerts table (LIVE/ACKNOWLEDGED; user-actionable alerts w/ JSON trade snapshot) |
| V100__create_api_tokens.sql | api_tokens table (written by upstox-auth) |
| V101__seed_reference_data.sql | Seeds NIFTY_LOT_SIZE = 65 into reference_data |
| V113__create_sim_clock.sql | `sim_clock` singleton — virtual-clock backing store for the simulation harness; read only under the `simulation` profile, inert in prod |

**Next migration: V114** ⚠️ NEVER use V10–V99 — those versions are numerically < V100 which is already applied; Flyway rejects them as out-of-order. (V109–V113 already exist.)
**Migration location:** `db-migrations/src/main/resources/db/migration/` — single source of truth; agent1, agent2, and agent3 pull scripts via Maven dependency

---

## What Changed From CLAUDE.md Spec

These are non-obvious deviations — don't re-derive them from the spec.

| Spec says | Reality |
|---|---|
| Agent 5 uses Zerodha Kite | **Agent 5 uses Upstox v2 Order API** (`api-hft.upstox.com`) |
| `StrategyType` enum | Enum is named **`Strategy`** (not `StrategyType`) |
| No SpreadDirection | **`SpreadDirection` enum added** (CREDIT / DEBIT) |
| TradeStatus has 6 values | **Two extra: `EXIT_IN_PROGRESS`, `EXIT_FAILED`** (duplicate exit guard) |
| RecommendRequestDto(userId, signalId) | **Has third field: `relaxedGate1PopPct`** (null = standard 80%; non-null overrides G1 for readjustment re-entry) |
| Agent 5 places both legs simultaneously via multi/place (§10) | **Agent 5 places legs SEQUENTIALLY, protective (BUY) leg first** (July 2026). One-at-a-time place→poll→next; on any leg failure the sequence stops and filled legs are reversed (`rollback`). Eliminates the naked-leg orphan risk of batch placement. Exit still uses a single batch reverse-MARKET multi/place. |
| Every external API call has retry (max 3) (§2 rule 3) | **Order PLACEMENT is never retried** (retry=0). It is not idempotent — a 5xx/timeout may mean the order landed, so a retry could duplicate it. On placement failure Agent 5 reconciles by tag and drives to flat instead. Idempotent calls (status, tag query, margin, funds, cancel) keep retry=3. |
| Module list: 7 modules | **Extra modules: `core-module`, `ledger-module`, `upstox-auth`, `ui-design`** |
| ShedLock config in spec | **ShedLock table exists (V9) but NOT wired to agent3 MonitorSchedulerService yet** |

### Critical convention — Upstox buyer PoP
`TradeLegDto.pop()` stores Upstox's **buyer's PoP** (probability the option expires ITM, 0–1 scale).
`GateValidator.validateG1()` computes: `seller_pop = (1 - shortLeg.pop()) × 100`
So for an OTM short put with seller_pop=87%: `shortLeg.pop() = 0.13` (not 0.87).
This convention applies everywhere: tests, clients, monitor config.

### G1 relaxed gate (readjustment re-entry)
When `RecommendRequestDto.relaxedGate1PopPct` is non-null, GateValidator uses that value as the G1 threshold instead of the standard 80%. Agent 3's `ReadjustmentService` passes 65% (normal VIX ≤ 22) or 70% (stressed VIX > 22).

---

## What's Built — Per Module

### shared-domain
- All enums (Bias, Strength, VixRegime, IvRegime, Strategy, SpreadDirection, TradeStatus, OptionType, LegAction, MonitorAction, ConfidenceLabel)
- Key DTOs: Agent1SignalDto, TradeCardDto, TradeLegDto, MonitorConfigDto, MonitorThresholdsDto, RecommendRequestDto, TradeConfirmRequestDto, ExitTradeRequest, GateResultDto
- TradingConstants, custom exceptions (DataFetchException, InsufficientDataException, GateValidationException, TokenRefreshException)

### core-module
- `AlertService` — writes to notifications table; methods: critical(), warning(), info(); never throws
- `AlertAutoConfiguration` — auto-configures in any module that has a JdbcTemplate
- `UpstoxClient` — market data (option chain, historical OHLC, spot, VIX)
- `UpstoxPositionClient` — GET /v2/portfolio/positions (for position reconciliation)
- `UpstoxAutoConfiguration` — registers both RestClient beans

### ledger-module
- `TradeLedgerService` — event-sourcing writes to trade_ledger; all writes in REQUIRES_NEW transactions
- `LedgerEventType` enum — covers full trade lifecycle events

### agent1-market_analyst (port 8081)
- Full 5-tier scoring pipeline (Strategy pattern: one `TierScorer` per tier)
- CommentaryExtractorService — Spring AI → Claude claude-sonnet-4-6 → JSON extraction; parse failure = neutral, never throws
- FII/DII snapshot persistence (daily, NSE CSV download) + 5-day trend in score_breakdown JSONB
- Gift Nifty from Upstox (primary) with fallback
- Marketaux news sentiment (3 articles, ^NSEI entity average)
- TA4J for EMA (20/50/200), RSI(14), MACD, candlestick patterns
- REST: POST /api/v1/agent1/score, GET /api/v1/agent1/latest, GET /api/v1/agent1/health

### agent2-recommendation (port 8082)
- Full 5-layer algorithm: strategy selection → expected move → strike selection → gate validation → position sizing
- GateValidator: G1 PoP (standard 80% or relaxed override), G2 max loss (INDICATIVE), G3 PoPP gap ≤ 15%, G4 RoC ≥ 0.5%×(DTE/5)
- Black-Scholes PoP via Apache Commons Math NormalDistribution
- REST: POST /api/v1/agent2/recommend, POST /api/v1/agent2/confirm, GET /api/v1/agent2/monitor-config/{tradeId}

### agent3-monitor (port 8083)
- `MonitorSchedulerService` — runs every 5 min 9:15–3:30 PM; handles HOLD/WATCH/READJUST/EXIT actions
- `ReadjustmentService` — 6-step automated exit+re-entry: DTE guard → exit old trade → fresh Agent1 signal → VIX-adjusted relaxed PoP → Agent2 recommend → confirm → Agent5 execute
- `PositionReconciliationService` — detects externally closed positions via Upstox positions API
- `Agent5ExitClient`, `Agent1ScoreClient`, `Agent2RecommendClient`, `Agent5ExecuteClient`
- EXIT_IN_PROGRESS guard on scheduler side (before calling Agent 5) prevents duplicate exits
- **ShedLock WIRED** — `@EnableSchedulerLock` on `Agent3MonitorApplication`, `@SchedulerLock` on `runMonitoringCycle()`, `ShedLockConfig` provides `LockProvider`, shedlock-spring + shedlock-provider-jdbc-template v6.3.0 in agent3 pom + parent dependencyManagement
- **GAP 1 FIXED** — `resolveMonitorConfig()` in MonitorSchedulerService: if `monitor_config` is null but `entry_fills` is present, parses fill prices (SELL→shortFill, BUY→longFill) and calls `Agent2RecommendClient.fetchMonitorConfig()` to seed the config. Agent2 writes to DB; subsequent cycles use the cached value.
- `TradeMonitorData` record has 8th field `entryFillsJson`; SQL in `TradeMonitorReader` selects `entry_fills`; `Agent2RecommendClient.fetchMonitorConfig()` added (GET with X-Short-Fill-Price / X-Long-Fill-Price headers)
- **Increment 1 (simulation):** `Clock` injection (`ClockConfig`, `now(clock)` at DTE/market-hours/staleness/timestamp sites in MonitorEvaluationService/MonitorSchedulerService/ReadjustmentService), `sim/` package (`SimClock`/`SimClockService`/`SimClockConfig`/`SimClockController` + V113 `sim_clock`), `MonitorActionRouter` (routing extracted from the scheduler — which lost 5 deps + 7 imports), and `POST /evaluate?act=true` (sandbox/simulation-gated push to Agent 5). See the **Simulation harness** section below.

### agent5-execution (port 8085)
- `UpstoxOrderClient` — POST /v2/order/multi/place, GET status, PUT modify, DELETE cancel
- `TradeExecutionService` — entry (**sequential, protective-leg-first, with rollback compensation**) + exit (reverse MARKET order)
  - **Entry placement is sequential, not batch (July 2026):** legs are placed one at a time (place → poll to fill → next) via `protectiveFirstOrder()` — BUY/protective legs first, SELL/premium legs last. On any leg failure the sequence STOPS and already-filled legs are reversed via `rollback()`. Because we never have un-polled orders resting on the exchange, there are no orphaned/naked-leg risks. Protective-first also means a mid-sequence failure only ever leaves a defined-risk long, and each short is placed with its hedge already held (SPAN spread margin, not naked-short margin). `rollback()` fires `alertService.critical("rollback_failed", …)` if a reverse order itself fails.
  - **Partial-fill compensation:** `pollToCompletion` returns a `PollResult` capturing partial fills on reject/cancel/timeout paths; a partial is reversed at its **actual filled qty**, never the ordered qty.
  - **Ambiguous-placement-failure reconciliation (issue #2, July 2026):** `placeMultiOrder` retry count is **0** (`UpstoxOrderClient.withRetry(op, 1, …)`) — order placement is never retried (a 5xx/timeout could mean the order landed; retry would duplicate it). On a placement throw, `reconcileAndFlatten()` runs: wait `reconcileDelayMs` (default 1000ms) → `getOrderDetailsByTag()` (new, retryable GET) → union book orders (exact tag, excludes our `_RB`/`_X`) with in-memory known fills (dedup by correlation_id) → per order: cancel-if-open → re-read → reverse confirmed fill (convergent drive-to-flat). Confirmed flat → `REJECTED`; otherwise `RECONCILE_REQUIRED` (new `TradeStatus`, no scheduler acts on it). **Every** reconciliation (success or failure, incl. empty/"nothing found" and query-failed) writes a `critical_alerts` row with a transparent trade-state JSON snapshot for manual user action.
  - `CriticalAlertService` (core-module, auto-configured next to `AlertService`) writes the `critical_alerts` table (V112); `TradeStatus.RECONCILE_REQUIRED` added.
- `ExecutionController` — POST /api/v1/agent5/execute, POST /api/v1/agent5/exit/{tradeId}; **enhanced health** returns `{status, timestamp, dbConnected, upstoxTokenLoaded}`; **new** `GET /api/v1/agent5/upstox/status` returns live Upstox connectivity response (tokenStatus, productionApiReachable, userId, sandboxTokenConfigured, orderGateway)
- `UpstoxConnectionCheckService` — calls `GET /v2/user/profile`; returns `UpstoxStatusResponse` with LOADED/ABSENT/EXPIRED/UNREACHABLE; never throws; exposes `isTokenLoaded()` for health endpoint
- **Upstox v3 order API (July 2026):** place/modify/cancel use `/v3/order/place`, `/v3/order/modify`, `/v3/order/cancel`. Order **reads stay v2** (no v3 read exists): single status via `/v2/order/details?order_id=`; **by-tag via `/v2/order/history?tag=`** (by-tag lookup is on Order History, NOT Order Details). Reads use a **dedicated `upstoxOrderReadRestClient`** (core-module, base = `upstox.api.order-read-base-url`) — order reads are NOT served on the HFT placement host `api-hft`, so reads route to `api.upstox.com` (prod) / `api-sandbox.upstox.com` (sandbox), with the same token as the order client. v2 order endpoints are NOT served on the sandbox host, and v3 is the current standard. Sandbox host confirmed `api-sandbox.upstox.com` (the docs' `sandbox.upstox.com` does not resolve).
- **Tag replaces correlation_id (v3 has none):** each leg carries a UNIQUE tag via `OrderTagBuilder.entryTag/exitTag/rollbackTag` (`ZUPP_{id8}_L{n}`, `_X_L{n}`, `_RB_L{n}`; ≤40 chars). One order placed per leg (`placeOrder`); v3 returns `data.order_ids[]` — we expect exactly one (no slicing).
- **No slicing:** a leg with quantity > `agent5.execution.max-order-quantity` (default 1755, the NIFTY freeze qty) is REJECTED before placement — the user/UI splits into two orders.
- **Ambiguous vs deterministic placement failure:** `UpstoxOrderException.isAmbiguous()` — 5xx/timeout ⇒ ambiguous ⇒ reconcile-by-tag; 4xx ⇒ deterministic ⇒ clean reject + rollback.
- Product `"D"` (NRML) for all spread legs — never `"I"` (intraday)
- Retry policy: idempotent reads (status, tag, margin, funds, cancel) retry 3× with a fixed **2s backoff** (`upstox.api.retry-delay-ms`, default 2000); **429 is retried** (not thrown); order **placement never retries** (retry=0). See `UpstoxOrderClient.withRetry`.
- `TradeExecutionRequestMapper` was **deleted** (July 2026) — dead code (no caller; orchestrator unbuilt) and only handled 2 legs. When the orchestrator lands it should build the N-leg `ExecuteTradeRequest` directly (the live `execute()` path is already N-leg; `Agent5ExecuteClient` in agent3 handles 4-leg Iron Condor).
- Sandbox IT (`TradeExecutionSandboxIT` T1–T5) written and ready — needs live tokens to run

- **Fault-mode simulation (Increment 1):** `dto/SimFillMode` (`FILL | SLIPPAGE | PARTIAL_ROLLBACK | TIMEOUT_MARKET | MARGIN_REJECT`) supplied per call via the `X-Sim-Fill-Mode` header; honored ONLY when `simulate-fills`/`simulate-exit` is on (the real order path ignores it → prod-safe). Entry: SLIPPAGE degrades fills → slippageAlert; PARTIAL_ROLLBACK/MARGIN_REJECT → REJECTED; TIMEOUT_MARKET → REJECTED (cancel) or ACTIVE (market) per `cancel-on-timeout`. Exit: any fault mode → simulated EXIT_FAILED. `execute`/`exit` gained 2-arg overloads (1-arg preserved for existing callers + the 26 unit tests). The `simulate-fills` path now also bypasses the Upstox margin call → fully offline.
- **`trade_pnl` on close (Increment 1, contract §7):** `writeTradePnlOnClose()` upserts the terminal `trade_pnl` row (`position_status=CLOSED`) on both success-close paths; realised P&L = latest `monitoring_evaluations.mark_to_market_pnl` for the trade (the MTM at the exit trigger). Idempotent per `(trade_id, snapshot_date)`, best-effort. **First writer of `trade_pnl` in the system.** Caveat: `snapshot_date` uses Agent 5 real time (no `Clock` injected in agent5 yet).

### Simulation harness (Increment 0 + 1)

Offline Phase-B simulator — see `test-data/simulation/` and the frozen `SIMULATION_PHASE_CONTRACT.md`.

- **Increment 0** (`test-data/simulation/`, zero prod impact): golden fixtures (bull-put, iron-condor), Phase-B scenario JSONs, `seed_golden_active.sql`, `run_phase_b.sh` conductor. Drives the Agent 3 decision ladder via the `/evaluate` override body. Golden fixtures are **PROVISIONAL** until captured from a real Phase-A run (contract §5).
- **Increment 1** — the virtual clock + act seam + Agent 5 fault modes + `trade_pnl` (detailed in the agent3 / agent5 sections above). **Full offline loop:** set virtual clock (`/sim/clock/set`) → `POST /evaluate/{id}?act=true` with an override body → decision → Agent 5 sandbox push (with fault modes) → trade CLOSED + `trade_pnl`. Run all agents with `-Dspring.profiles.active=sandbox,simulation`.
- **Prod safety:** every sim bean is `@Profile("simulation")`; `act=true` requires sandbox/simulation; fault modes require `simulate-fills`; `SimClockService` logs a loud startup warning. **TODO:** a hard "fail-fast if `simulation` active in a prod environment" assertion (test-matrix F11).
- **Still open:** capture real golden fixtures + §5 contract test; expiry `trade_pnl` positions-sync (§7); inject `Clock` into agent5 for virtual close dates.

### zupptrade-ui (Angular 18, port 4200)
- Standalone components, Angular Material, SCSS
- `DashboardStateService` — two RxJS polling loops: Agent1 `/latest` every 10s, Agent3 `/active-trades` every 5s
- `apiKeyInterceptor` — adds `X-API-Key` header to all requests from `environment.ts`
- `proxy.conf.json` — dev proxy routes `/api/agentN` → `localhost:808N/api/v1/agentN`
- **Nav:** IST live clock, LIVE badge, refresh button
- **Market Strip:** collapsible — shows Nifty/VIX/Bias/Score/Confidence/Age; expands to tier breakdown
- **Recommendation panel (left):** 4-state machine — Ready → Loading → TradeCard (legs/metrics/gates/thresholds) → Rejected → Active Entry
- **Live Monitor (right):** active trade cards with threshold bars (T1/T2/T3), live P&L, alert badges; empty slots; P&L summary (Open P&L live, historical stubbed)
- **Shared components:** BiasPill, ConfidencePill, ThresholdBar, MetricBox, GateBadge
- **Agent 3 backend change:** Added `GET /api/v1/agent3/active-trades` → `ActiveTradeDto` (MonitorConfig + latest evaluation snapshot)
- **Run:** `cd zupptrade-ui && npm start` (or `npx @angular/cli@18 serve`)

---

## Pending Tasks

### Session A — Security & Token (agent1 only, independent)
- **#10** — X-API-Key filter: add Spring Security filter chain to agent1; validate `X-API-Key` header against env var `X_API_KEY`; return 401 on mismatch
- **#9** — Expired token response: when Upstox returns 401, return a structured 503 response (not a stack trace); log the event; do NOT retry (token refresh is manual in v1)

### Session B — Agent5 verify + sandbox (independent)
- ✅ **Verified** TradeExecutionService: margin check, 30s fill poll, LIMIT→MARKET fallback, rollback on leg failure, slippage alert — all implemented and unit tested
- ✅ **Exit flow unit tests** added to `TradeExecutionServiceTest`: 11 new tests covering ACTIVE/EXIT_IN_PROGRESS/EXIT_FAILED happy paths, CLOSED/REJECTED/null early returns, placement failure, payload error, action reversal (27 unit tests total, all green)
- ✅ **upstox-auth checked**: `TokenRefreshScheduler` runs at startup (ApplicationRunner) + scheduled at 08:30 AM IST weekdays — complete
- ✅ **Session 2 additions (40 tests total, all green):**
  - `UpstoxConnectionCheckService` + `UpstoxStatusResponse` (LOADED/ABSENT/EXPIRED/UNREACHABLE; 9 unit tests)
  - `ExecutionController` enhanced: `/health` includes `dbConnected` + `upstoxTokenLoaded`; new `GET /upstox/status` live check
  - _(`TradeExecutionRequestMapper` from this session was later deleted as dead code — see agent5 section)_
- ⏳ **Sandbox test** (`TradeExecutionSandboxIT` T1–T5) written and ready; requires live env vars: `UPSTOX_ACCESS_TOKEN`, `UPSTOX_SANDBOX_TOKEN` + NeonDB. Run: `mvn test -pl agent5-execution "-Dexcluded.test.groups=" -Dgroups=sandbox -Dspring.profiles.active=sandbox,local`

### Session C — Scheduling & wiring (each agent owns its own schedule; no orchestrator)
- **#18** — Pre-Agent-2 candle check: Agent2 itself (or UI before calling Agent2) verifies the current 5-min candle is not an anomaly (spike/gap); if anomaly detected, reject with 422 and alert
- Morning scheduled runs are owned by each agent (Agent1 @Scheduled 9:00 AM + 9:20 AM; Agent3 @Scheduled every 5 min market hours) — no central coordinator
- Confirmation flow: UI → Agent2 /confirm → Agent5 /execute (direct call, no middleman)

### Session D — Agent1 data quality (independent)
- **#5** — Backtest scenario validation: run POST /api/v1/agent1/score with mocked inputs (spot=23412.60, 20EMA=23900, 50EMA=23690, PCR=1.17, FII long ratio=0.11, DII net=684Cr, VIX=18.61, VIX prev=19.43, Gift Nifty +70pts, Marketaux=-0.335); expected: NEUTRAL/WEAK, score≈0.067, confidence=LOW
- ~~**#11**~~ — **DONE**: `ExpiryDateService` in core-module reads from `reference_data` (key=`NIFTY_EXPIRY_DATES`, TTL=7 days); falls back to `UpstoxExpiryClient` (`GET /v2/option/contract`). `ScoreRequestDto.expiryDate` now optional — auto-resolved when absent. New endpoint: `GET /api/v1/agent1/next-expiry`.
- **#16** — Evaluate Highest OI strike (Call Wall / Put Wall): fetch strikes with top 3 OI from option chain; if spot is within 100pts of a Call Wall → bearish signal; if within 100pts of a Put Wall → bullish signal; add as optional Tier 1A or Tier 3 signal

### Session E — Refactors (independent, low risk)
- **#6** — core-module cleanup: move Upstox market data client classes to agent1 where they belong; keep AlertService + UpstoxPositionClient (used by agent3) in core-module
- **#12** — Agent1SignalEntity: exists separately in agent1 and agent2; create one shared entity in shared-domain or agent1; agent2 reads via DTO (already the case via agent1_signals table)
- ~~**#13**~~ — DONE: `db-migrations` module created; V1–V9 + V100 consolidated; agent2 + agent3 both depend on it; agent2 local SQL files deleted; agent3 flyway enabled
- **#19** — Strategy matrix symmetry (agent2 Layer 1, `StrategySelector.selectBullish`): WEAK **bearish** reroutes to `selectNeutral` (can yield Iron Condor when IV rich), but WEAK **bullish** just `skip()`s. Introduced in commit 9b4b672 (Iron Condor) — the weak→neutral reroute was mirrored to bearish only; likely an oversight (the "harvest IV richness" rationale is direction-agnostic, and the spec's "Any + Weak" language implies uniform handling). Fix: reroute WEAK bullish to `selectNeutral(WEAK, vixRegime, ivRegime)` + unit test. No effect on cheap-IV/low-VIX (still SKIP); only changes rich-IV NORMAL/HIGH-VIX weak-bullish → Iron Condor, matching bearish. Low risk, agent2-only.

### Session F — Backtest ✅ COMPLETE
- **agent4-backtest** — DONE. Read-only analytics. 5 REST endpoints (/summary, /trades, /trades/{id}/audit, /signal-quality, /health). 37 unit tests + integration tests. Angular "Audit" tab wired. docker-compose service: `agent4`.

### Parked indefinitely
- OAuth2 auth (replace X-API-Key) — design allows swap without touching business logic
- #8 — Gemini free tier quota fix (not blocking anything)

---

## How to Start a Session

Tell Claude: **"Read CLAUDE.md and IMPL_STATUS.md, then work on Session X — [task name]."**

Claude will load the spec + this file and have full context without needing conversation history.
Update the "What's Built" section and cross off tasks from Pending when a session completes.

**For integration testing:** Tell Claude: *"Read CLAUDE.md, IMPL_STATUS.md, and INTEGRATION_TEST_GUIDE.md, then help me run integration tests."*
`INTEGRATION_TEST_GUIDE.md` contains pre-flight DB checks, per-agent test scenarios (S1.1–S5.3), DB seed SQL for Agent 3 scenarios, end-to-end flow test, and cleanup SQL.

---

*Last updated: 2026-07-26 — SIMULATION HARNESS Increment 1 (offline Phase-B). Agent 3: `ClockConfig` (prod `Clock` bean, IST, `@Profile("!simulation")`) + `now()`→`now(clock)` at DTE/market-hours/staleness/audit-timestamp sites; new `sim/` package (`SimClock`/`SimClockService`/`SimClockConfig`/`SimClockController`) backed by **V113 `sim_clock`**; `MonitorActionRouter` — EXIT/READJUST/PAUSE routing extracted from `MonitorSchedulerService` (scheduler lost 5 deps + 7 imports, delegates to router; no drift); `POST /evaluate?act=true` (controller-gated to sandbox/simulation) routes a decision to Agent 5 AFTER the evaluate tx commits. Agent 5: `SimFillMode` + `X-Sim-Fill-Mode` header (FILL/SLIPPAGE/PARTIAL_ROLLBACK/TIMEOUT_MARKET/MARGIN_REJECT), `execute`/`exit` 2-arg overloads, simulate path now fully offline (bypasses Upstox margin), `writeTradePnlOnClose()` (first `trade_pnl` writer; realised = latest monitoring_evaluations MTM). Increment 0 folder `test-data/simulation/` (golden fixtures + scenarios + conductor). All changes additive/`@Profile`-gated; NOT compiled here — run `mvn compile` on agent3-monitor + agent5-execution. Contract frozen in `SIMULATION_PHASE_CONTRACT.md`. Open: capture real golden fixtures + §5 test; expiry trade_pnl (§7); agent5 Clock; hard prod-guard assertion (F11).*

*Last updated: 2026-07-25 (5) — ALL critical alerts now fan out to the critical_alerts DB table: `AlertService.critical()` (core-module) records to critical_alerts in addition to notifications, so EVERY critical failure from any agent (agent5 rollback/exit, all of agent3's readjust/exit criticals, etc.) surfaces on the UI critical-alert card — one central change, no per-call-site edits. Reconcile keeps its own rich-JSON direct record. New AlertServiceTest. NOTE STILL OPEN (de-scoped by user): exit is not idempotent — Agent3 retry of EXIT_FAILED re-reverses already-closed legs (double-reverse). Deferred; failures are at least all captured in critical_alerts now.*

*Last updated: 2026-07-25 (4) — Agent5 order READS moved to a dedicated `upstoxOrderReadRestClient` (core-module) on `upstox.api.order-read-base-url` (prod api.upstox.com / sandbox api-sandbox) — fixes reads being wrongly pointed at the HFT placement host `api-hft` which doesn't serve them. New config `order-read-base-url` in application.yml + application-sandbox.yml. UpstoxOrderClient now takes 3 RestClients. Also: sandbox by-tag lookup fixed to `/v2/order/history?tag=` (was `/v2/order/details`). Sandbox profile: simulate-fills/exit=false, bypass-margin-check=true (zero capital). All agent5 unit tests green.*

*Last updated: 2026-07-25 (3) — Agent5 order client MIGRATED to Upstox v3 (place/modify/cancel → /v3/order/*; reads stay /v2/order/details). correlation_id replaced by a unique per-leg `tag` (v3 has no correlation_id). No auto-slicing — legs > freeze qty (agent5.execution.max-order-quantity, default 1755) rejected up front. Sandbox host confirmed api-sandbox.upstox.com; sandbox needs a distinct Sandbox-App token + v3 endpoints (v2 order calls 401 on sandbox). Deleted MultiOrderRequest/MultiOrderResponse; added PlaceOrderV3Request/Response. Rewrote UpstoxOrderClient, TradeExecutionService, OrderTagBuilder, and all agent5 tests (unit + sandbox IT + probe). New diagnostic: SandboxCapabilityProbeIT.*

*Last updated: 2026-07-25 (2) — Agent5 issue #2: order placement retry set to 0 (never retry a non-idempotent placement) + ambiguous-failure `reconcileAndFlatten` (query order book by tag → convergent drive-to-flat → REJECTED if flat else RECONCILE_REQUIRED). New: `critical_alerts` table (V112), `CriticalAlertService` (core-module), `TradeStatus.RECONCILE_REQUIRED`, `UpstoxOrderClient.getOrderDetailsByTag` + `TaggedOrdersResponse`, `agent5.execution.reconcile-delay-ms`. New tests: reconcile happy/empty/query-fail/exclude-_RB/reverse-fail (TradeExecutionServiceTest), UpstoxOrderClientTest (retry policy + tag parse), CriticalAlertServiceTest. Partial-fill compensation also added (reverse actual filled qty, not ordered).*

*Last updated: 2026-07-25 — Agent5 entry placement reworked from batch multi/place to SEQUENTIAL protective-leg-first (long→short) with rollback compensation — fixes naked-leg orphan risk on poll-time partial rejection; rollback now fires alertService.critical("rollback_failed") on failure. agent5 unit tests 60 green (TradeExecutionServiceTest 19→23: protective ordering, short-rejected-after-long rollback, long-rejected clean stop, timeout rollback, Iron Condor mid-sequence rollback, rollback-failure alert).*

*Prior (2026-06-25): agent4-backtest fully implemented (analytics/audit, 5 REST endpoints, 37 unit tests, integration tests, Angular Audit tab, docker-compose wired). New Dockerfiles: agent1, agent5, upstox-auth. agent2/agent3 Dockerfiles updated to eclipse-temurin:21-jre-jammy. docker-compose.yml created. V102 migration adds data_gaps column. V103 adds spread_direction column. V104 adds DB views v_agent4_trade_list and v_agent4_signal_quality.*
