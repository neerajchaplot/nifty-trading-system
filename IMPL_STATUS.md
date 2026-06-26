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
| V100__create_api_tokens.sql | api_tokens table (written by upstox-auth) |
| V101__seed_reference_data.sql | Seeds NIFTY_LOT_SIZE = 65 into reference_data |

**Next migration: V102** ⚠️ NEVER use V10–V99 — those versions are numerically < V100 which is already applied; Flyway rejects them as out-of-order.
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

### agent5-execution (port 8085)
- `UpstoxOrderClient` — POST /v2/order/multi/place, GET status, PUT modify, DELETE cancel
- `TradeExecutionService` — entry (multi-leg simultaneous) + exit (reverse MARKET order)
- `ExecutionController` — POST /api/v1/agent5/execute, POST /api/v1/agent5/exit/{tradeId}; **enhanced health** returns `{status, timestamp, dbConnected, upstoxTokenLoaded}`; **new** `GET /api/v1/agent5/upstox/status` returns live Upstox connectivity response (tokenStatus, productionApiReachable, userId, sandboxTokenConfigured, orderGateway)
- `UpstoxConnectionCheckService` — calls `GET /v2/user/profile`; returns `UpstoxStatusResponse` with LOADED/ABSENT/EXPIRED/UNREACHABLE; never throws; exposes `isTokenLoaded()` for health endpoint
- `TradeExecutionRequestMapper` — static utility: converts Agent2 `TradeCardDto` → Agent5 `ExecuteTradeRequest`; quantity = lots × lotSize per leg; shortLeg first (SELL), longLeg second (BUY); called by the UI (or Agent2 directly) after `/confirm`
- Order tag format: `ZUPP_{tradeId_first8}` (shared across legs), `ZUPP_{id8}_L{n}` per leg
- Product `"D"` (NRML) for all spread legs — never `"I"` (intraday)
- Unit tests: **40 green** (8 OrderTagBuilder + 8 execute + 11 exit + 4 mapper + 9 connection-check)
- Sandbox IT (`TradeExecutionSandboxIT` T1–T5) written and ready — needs live tokens to run

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
  - `TradeExecutionRequestMapper` — Agent2 TradeCardDto → Agent5 ExecuteTradeRequest (4 unit tests)
  - `ExecutionController` enhanced: `/health` includes `dbConnected` + `upstoxTokenLoaded`; new `GET /upstox/status` live check
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

*Last updated: 2026-06-25 — agent4-backtest fully implemented (analytics/audit, 5 REST endpoints, 37 unit tests, integration tests, Angular Audit tab, docker-compose wired). New Dockerfiles: agent1, agent5, upstox-auth. agent2/agent3 Dockerfiles updated to eclipse-temurin:21-jre-jammy. docker-compose.yml created. V102 migration adds data_gaps column. V103 adds spread_direction column. V104 adds DB views v_agent4_trade_list and v_agent4_signal_quality.*
