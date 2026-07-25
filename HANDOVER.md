# ZuppTrade — New Agent Session Handover

> **How to use this file:** Read `CLAUDE.md` (the spec) first, then this file.
> This file captures every deviation from the spec, all established conventions,
> architectural decisions made in code, and the current pending task list.
> A new agent reading CLAUDE.md + HANDOVER.md + IMPL_STATUS.md has full context.

---

## 1. PROJECT IDENTITY

**Product name:** ZuppTrade  
**Base package:** `com.the3Cgrp.zupptrade` (NOT `com.nifty` — the spec was updated early on)  
**Module packages:** `com.the3Cgrp.zupptrade.agent1`, `.agent2`, `.agent3`, `.agent5`, `.shared`  
**Project root on disk:** `C:\3CGrp\nifty-trading-system\`  
**UI repo on disk:** `C:\3CGrp\nifty-trading-system\zupptrade-ui\` (Angular 18)  
**Mobile repo on disk:** `C:\3CGrp\nifty-trading-system\zupptrade-mobile\` (Ionic/Angular)

---

## 2. ACTUAL MODULE MAP (what's on disk — differs from CLAUDE.md spec)

```
nifty-trading-system/
├── shared-domain/          COMPLETE — DTOs, enums, constants
├── core-module/            COMPLETE — AlertService, UpstoxClient, auto-config (NOT deployable)
├── ledger-module/          COMPLETE — TradeLedgerService (event sourcing)
├── db-migrations/          COMPLETE — ALL Flyway SQL lives here; agents depend on it via Maven
├── agent1-market_analyst/  COMPLETE — port 8081, market direction scoring
├── agent2-recommendation/  COMPLETE — port 8082, trade recommendation
├── agent3-monitor/         COMPLETE — port 8083, monitoring + readjustment
├── agent5-execution/       MOSTLY COMPLETE — port 8085, Upstox order execution
├── agent4-backtest/        COMPLETE — port 8084, read-only analytics
├── upstox-auth/            STATE UNKNOWN — daily token refresh via Playwright
├── zupptrade-ui/           COMPLETE — Angular 18, port 4200
├── zupptrade-mobile/       IN PROGRESS — Ionic/Angular mobile app
└── ui-design/              HTML wireframe (design reference)
```

**NO ORCHESTRATOR MODULE EXISTS.** This is a firm decision never to be revisited.
Each agent owns its scheduling. Agent 3 calls Agent 5 directly for EXIT.
The UI calls agents directly (Agent1 → Agent2 → Agent5 in sequence).

---

## 3. CRITICAL SPEC DEVIATIONS

These are confirmed implementation decisions that differ from CLAUDE.md. Never re-derive from the spec.

| CLAUDE.md spec says | Actual implementation |
|---|---|
| Agent 5 uses Zerodha Kite | **Agent 5 uses Upstox v2 Order API** (`api-hft.upstox.com`) |
| Base package `com.nifty` | **`com.the3Cgrp.zupptrade`** |
| `StrategyType` enum | Enum is named **`Strategy`** (without "Type") |
| No SpreadDirection | **`SpreadDirection` enum exists** — CREDIT or DEBIT |
| TradeStatus has 6 values | **8 values: adds `EXIT_IN_PROGRESS`, `EXIT_FAILED`** |
| RecommendRequestDto(signalId, profileId) | **Has 3rd field: `relaxedGate1PopPct`** (null = standard 80%) |
| FII/DII from NSE CSV | **FII/DII from Upstox `/v2/market/fii` and `/v2/market/dii`** — NEVER NSE |
| Orchestrator module | **No orchestrator — each agent owns its schedule** |
| Module list: 7 modules | **Extra modules: `core-module`, `ledger-module`, `db-migrations`, `upstox-auth`, `ui-design`** |
| trade_code format T-YYYYMMDD-XXXX | **Actual format: `TRD-YYYYMMDD-NNNN`** |

---

## 4. NON-NEGOTIABLE CODING CONVENTIONS

These apply everywhere. Never violate.

### BigDecimal for all money and scores
Never use `float` or `double` for any financial calculation, scoring, or threshold comparison.
Use `BigDecimal` throughout. Convert to `double` only for Apache Commons Math functions (Black-Scholes),
then convert the result back to `BigDecimal`.

### Constructor injection only
Never use `@Autowired` field injection. Always inject via constructor.
```java
// CORRECT
public class MyService {
    private final UpstoxClient client;
    public MyService(UpstoxClient client) { this.client = client; }
}

// NEVER DO THIS
@Autowired
private UpstoxClient client;
```

### JSONB fields need @JdbcTypeCode
Any `String` field backed by a PostgreSQL `JSONB` column requires TWO annotations:
```java
@Column(columnDefinition = "jsonb")   // DDL only
@JdbcTypeCode(SqlTypes.JSON)          // runtime type binding — WITHOUT THIS, Hibernate binds as VARCHAR and inserts fail
private String myJsonField;
```
This was a hard-won lesson. Missing `@JdbcTypeCode` causes silent INSERT failures that roll back the outer transaction.

### Upstox buyer PoP convention
`TradeLegDto.pop()` stores Upstox's **buyer's PoP** — the probability the option expires ITM, on a 0–1 scale.
To get seller's PoP (what the system cares about), compute: `(1 - shortLeg.pop()) × 100`

Example: An OTM short put with seller's PoP = 87% → `shortLeg.pop() = 0.13`

This convention applies everywhere: `GateValidator.validateG1()`, monitor config, tests, clients.

### API keys — env vars only
API keys and tokens go in environment variables. Never in `application.yml` or code.
URLs are NOT secrets — they belong in `application.yml`.

### External API error handling
Every external API call wrapped in try-catch. On failure:
- Missing data → score 0, log to `data_gaps`, never throw
- LLM failure → neutral signal, never block scoring pipeline
- API key expired → return structured 503, do NOT retry

### No hardcoded values
Lot size (65), VIX thresholds, expiry dates, scoring weights all come from DB or `application.yml`.
Never hardcode them in Java code.

### Logging
Use structured logging with key-value pairs: `log.info("tier1a.scored", kv("score", score), kv("weight", weight))`
Every trade calculation logged with full input + output for audit.

---

## 5. DATABASE

### Schema
All tables live in PostgreSQL schema **`zupptrade_dev`** (NOT `public`).
On a fresh DB: `CREATE SCHEMA IF NOT EXISTS zupptrade_dev;` first — Flyway won't auto-create it.

JDBC URL must include: `currentSchema=zupptrade_dev`
HikariCP: `connection-init-sql: "SET search_path TO zupptrade_dev"`
Flyway: `init-sql: "SET search_path TO zupptrade_dev"`, `default-schema: zupptrade_dev`

### Flyway — CRITICAL VERSION RULE
All migrations in `db-migrations/src/main/resources/db/migration/`.
Agent1, Agent2, Agent3 declare `db-migrations` as a Maven dependency. Agent5 has `flyway.enabled=false`.

**Applied migrations as of 2026-07-12:**
- V1–V9: core tables (agent1_signals, user_profiles, trades, reference_data, scoring_audit_log, fii_dii_snapshots, trade_code extension, trade_ledger, trade_executions, trade_pnl, monitoring_evaluations, notifications, shedlock)
- V100: api_tokens table
- V101: seed NIFTY_LOT_SIZE = 65
- V102: adds data_gaps column
- V103: adds spread_direction column
- V104: DB views for agent4 analytics

**NEXT MIGRATION: V105**
**NEVER use V10–V99** — those numbers are numerically less than V100 which is applied; Flyway rejects them.

### DB startup order (fresh deploy)
1. Create schema: `CREATE SCHEMA IF NOT EXISTS zupptrade_dev;`
2. Start **agent2 first** (runs all V1–V104 Flyway migrations, creates all tables including `api_tokens`)
3. Start **upstox-auth** (reads/writes `api_tokens` — does NOT run Flyway)
4. Start remaining agents in any order

### Connection details (Neon cloud)
- DB: Neon PostgreSQL (cloud-hosted)
- Schema: `zupptrade_dev`
- `ddl-auto: validate` in all agents (never create/update)
- Credentials in env vars: `DB_USER`, `DB_PASSWORD`, `DB_URL`

---

## 6. DATA ARCHITECTURE

### Upstox is the ONLY data source for market data AND orders
- Market data: `api.upstox.com` (production) / `api-sandbox.upstox.com` (sandbox)
- Order placement: `api-hft.upstox.com` (HFT endpoint for multi-leg orders)
- Both RestClient beans auto-configured by `core-module`'s `UpstoxAutoConfiguration`

### Key Upstox endpoints
- `GET /v2/option/chain?instrument_key=NSE_INDEX|Nifty 50&expiry_date=YYYY-MM-DD` — option chain (IV, delta, OI, LTP, PoP per strike)
- `GET /v2/historical-candle/{key}/day/{to}/{from}` — daily OHLC
- `GET /v2/market/fii?data_type=NSE_FO|INDEX_FUTURES&interval=1D&from=YYYY-MM-DD` — FII futures + long ratio
- `GET /v2/market/fii?data_type=NSE_FO|INDEX_OPTIONS&interval=1D&from=YYYY-MM-DD` — FII options
- `GET /v2/market/dii?data_type=NSE_EQ|CASH&interval=1D&from=YYYY-MM-DD` — DII cash
- `POST /v2/order/multi/place` — simultaneous multi-leg entry (on `api-hft.upstox.com`)
- `GET /v2/portfolio/positions` — position check for Agent 3 reconciliation
- `POST /v2/charges/margin` — margin check before placement

### FII/DII source — repeated correction
FII/DII data comes from Upstox `/v2/market/fii` and `/v2/market/dii` using the same Bearer token.
**NEVER from NSE CSV.** This has been corrected multiple times. Never revert to NSE.

FII response is returned newest-first; `entries.get(0)` is always the most recent trading session.

### Order identification
- **tag** (all legs of one trade): `ZUPP_{tradeId_first8_uppercase}` — queryable from Upstox
- **correlation_id** (per leg): `ZUPP_{id8}_L{n}` (1-indexed)
- **Exit tag**: `ZUPP_{id8}_X`; exit leg: `ZUPP_{id8}_X_L{n}`

### Product type
Always `"D"` (Delivery / NRML) for Nifty weekly spread legs — held until Tuesday expiry.
**Never `"I"` (Intraday)** — auto-squared at 3:20 PM.

---

## 7. TRADE LIFECYCLE & PERSISTENCE

### Trade statuses (full set)
```
PENDING_CONFIRM   → trade card generated, awaiting user approval
CONFIRMED         → user approved
REJECTED          → user rejected or hard gate failed (production mode)
EXPIRED           → card expired before confirmation
ACTIVE            → position open with broker
EXIT_IN_PROGRESS  → exit order placed, not yet confirmed (prevents duplicate exits)
EXIT_FAILED       → exit failed; Agent 3 retries next monitoring cycle
CLOSED            → position fully closed
```

### TradeLedgerService — REQUIRES_NEW
`TradeLedgerService` uses `@Transactional(propagation = REQUIRES_NEW)`.
The ledger write runs in its own independent transaction — persists even if the outer transaction rolls back.
This is intentional: we must never lose the audit record of a placed order.

### trade_code format
`TRD-YYYYMMDD-NNNN` — 4-digit zero-padded number from `trade_code_seq` PostgreSQL sequence.
Example: `TRD-20260710-0001`

### EXIT_IN_PROGRESS guard
Before calling Agent 5 to exit, Agent 3 scheduler sets status to `EXIT_IN_PROGRESS` in DB.
Agent 5's exit method also checks: if status == `EXIT_IN_PROGRESS` → return early (idempotent guard).
This prevents duplicate exit orders across 5-minute monitoring cycles.

---

## 8. AGENT 2 — RECOMMENDATION ENGINE INTERNALS

### 5-Layer algorithm (quick reference)
1. **StrategySelector** — decision matrix: Bias + Strength + VixRegime + IvRegime → Strategy
2. **ExpectedMoveCalculator** — EM = spot × IV × √(DTE/365); 1.4 SD boundary = 84% zone
3. **StrikeSelector** — short strike at/beyond 1.4 SD, delta ≤ 0.20, rounded to nearest 50
4. **GateValidator** — G1 PoP (≥80%), G2 max loss (indicative), G3 PoPP gap (≤15%), G4 RoC (≥0.5%×DTE/5)
5. **PositionSizer** — lots = floor(capital × 1.5% ÷ (maxLossPerLot × 0.5))

### RecommendationContext
The mutable context object flowing through all 5 layers. Key fields:
- `hardGateEnabled` — true = production gates enforce; false = testing mode
- `skipDecision` — true when Layer 1 would have returned SKIP/NO_TRADE (fallback used instead)
- `skipReason` — "SKIP", "NO_TRADE", or "VIX_EXTREME"
- `allHardGatesPassed` — set by GateValidator; forced to `true` in testing mode

### StrategySelector — always picks a real strategy
`StrategySelector.execute()` never returns SKIP/NO_TRADE to the engine.
If the decision matrix would return SKIP/NO_TRADE, `selectFallback()` is called instead:
- BULLISH + EXTREME → BULL_CALL_SPREAD (DEBIT)
- BULLISH other → BULL_PUT_SPREAD (CREDIT)
- BEARISH + EXTREME → BEAR_PUT_SPREAD (DEBIT)
- BEARISH other → BEAR_CALL_SPREAD (CREDIT)
- NEUTRAL → IRON_CONDOR (CREDIT)

The original SKIP decision is recorded in `ctx.skipDecision = true` and `ctx.skipReason`.

### G1 relaxed gate (readjustment)
When `RecommendRequestDto.relaxedGate1PopPct` is non-null, `GateValidator` uses that as the G1 threshold.
Agent 3's `ReadjustmentService` passes 65% (normal VIX ≤ 22) or 70% (stressed VIX > 22).

---

## 9. TESTING MODE / HARD GATE BYPASS (implemented 2026-07-12)

This feature enables testing when market conditions would normally return SKIP or fail gates.

### Backend flag
In `agent2-recommendation/src/main/resources/application.yml`:
```yaml
trading:
  hard-gate-enabled: true   # false = testing mode
```

Set via environment variable: `TRADING_HARD_GATE_ENABLED=false` to enable testing mode.

### `TradingConfig.java` (agent2)
```java
@ConfigurationProperties(prefix = "trading")
public class TradingConfig {
    private boolean hardGateEnabled = true;  // default: gates enforce
    ...
}
```

### What testing mode does
When `hardGateEnabled = false`:
1. `StrategySelector` still picks a fallback (this always happens regardless of mode)
2. `GateValidator` computes real gate values, then **forces `allHardGatesPassed = true`** at end
3. Engine never early-exits on gate failure
4. `RecommendationService` always sets status = `PENDING_CONFIRM` (never REJECTED)
5. `calculateOverride()` always returns `popBlocked = false`, `lossBlocked = false`
6. `TradeCardDto` carries `testingModeActive = true`

### What testing mode does NOT change
- Gate results still show real PASS/FAIL values in the UI (for visibility)
- `skipDecision` and `skipReason` still recorded accurately
- All calculations (PoP, RoC, lots, thresholds) still computed from real market data

### Status logic in RecommendationService
```java
if (!ctx.isHardGateEnabled()) {
    status = TradeStatus.PENDING_CONFIRM;  // testing: always allow
} else if (ctx.isSkipDecision()) {
    status = TradeStatus.REJECTED;         // production: skip = reject
} else if (!ctx.isAllHardGatesPassed()) {
    status = TradeStatus.REJECTED;         // production: gate fail = reject
} else {
    status = TradeStatus.PENDING_CONFIRM;  // production: all gates passed
}
```

### UI changes (zupptrade-ui + zupptrade-mobile)
- `TradeCard` model has 3 new fields: `testingModeActive`, `skipDecision`, `skipReason`
- Testing mode shows amber banner: "⚠️ TESTING MODE — hard gates bypassed."
- Skip decision shows red banner: "Skip Decision Overridden — original: {skipReason}. Fallback strategy applied."
- `isPopBlocked` getter returns `false` when `testingModeActive = true`
- `canConfirmOverrideBuilder` returns `true` when `testingModeActive = true`
- State transition: `state = (!card.testingModeActive && card.status === 'REJECTED') ? 'rejected' : 'tradecard'`
- Mobile: NEUTRAL/WEAK "Generate" button no longer disabled

---

## 10. AGENT 3 — KEY DESIGN DECISIONS

### No ShedLock ~~gap~~ — NOW WIRED
`@EnableSchedulerLock` on `Agent3MonitorApplication`, `@SchedulerLock` on `runMonitoringCycle()`, `ShedLockConfig` provides `LockProvider`. `shedlock-spring` + `shedlock-provider-jdbc-template` v6.3.0 in agent3 pom.

### ReadjustmentService — 6-step automated flow
On T2 READJUST: exit old trade → fetch fresh Agent1 signal → VIX-adjusted relaxed PoP → Agent2 recommend → confirm → Agent5 execute.
READJUST is implemented but the trigger fires conservatively (human action recommended first via alert).

### EXIT_IN_PROGRESS handshake (Agent 3 → Agent 5)
```
Agent3.triggerExit() → sets status = EXIT_IN_PROGRESS in DB → calls Agent5ExitClient.exit()
Agent5.exit() → if status == EXIT_IN_PROGRESS → return early (idempotent)
              → else → sets EXIT_IN_PROGRESS before calling Upstox
              → on success → sets CLOSED
              → on failure → sets EXIT_FAILED
Agent3 next cycle → picks up EXIT_FAILED → retries
```

### AlertService
All critical/warning events written to `notifications` table via `AlertService`.
`AlertService` never throws. Auto-configured by `AlertAutoConfiguration` in any module with JdbcTemplate.
Methods: `critical()`, `warning()`, `info()`.

### Agent 3 config in application.yml
```yaml
agent5:
  url: ${AGENT5_URL:http://localhost:8085}
agent1:
  url: ${AGENT1_URL:http://localhost:8081}
agent2:
  url: ${AGENT2_URL:http://localhost:8082}
```

---

## 11. UI ARCHITECTURE

### zupptrade-ui (Angular 18, port 4200)
- Standalone components, Angular Material, SCSS
- Dev proxy: `proxy.conf.json` routes `/api/agentN` → `localhost:808N/api/v1/agentN`
  - `/api/agent1` → `localhost:8081/api/v1/agent1`
  - `/api/agent2` → `localhost:8082/api/v1/agent2`
  - `/api/agent3` → `localhost:8083/api/v1/agent3`
  - `/api/agent5` → `localhost:8085/api/v1/agent5`
- `apiKeyInterceptor` — adds `X-API-Key` header from `environment.ts` to every request
- `DashboardStateService` — two RxJS polling loops (signal every 10s, trades every 5s)
- 4-state recommendation panel: Ready → Loading → TradeCard / Rejected → Active Entry
- `CalculateOverrideResult` model includes `testingModeActive: boolean`

### zupptrade-mobile (Ionic/Angular)
- Package: `@ionic/angular/standalone`; template inline in component class (not separate HTML file)
- Page state machine: `'ready' | 'loading' | 'tradecard' | 'active' | 'skip'`
- Testing mode and skip-decision banners in TRADECARD state
- `TradeCard` model matches UI model (same 3 extra testing-mode fields)
- **Run:** `cd zupptrade-mobile && ionic serve`

---

## 12. DEPLOYMENT (Azure)

- **Compute:** Azure VM `Standard_B2s` (2 vCPU / 4 GB), Ubuntu 22.04, Central India
- **Static public IP:** 20.219.165.3; FQDN: `zupptrade.centralindia.cloudapp.azure.com`
- **DB:** Azure PostgreSQL Flexible Server B1ms, host `zupptrade-pg.postgres.database.azure.com`, db `nifty_trading` (underscore, not hyphen), schema `zupptrade_dev`
- **DB URL must include:** `?sslmode=require`
- **Images:** Built by GitHub Actions (`build-and-push.yml`), pushed to `ghcr.io/<owner>/zupptrade-<svc>:{latest,sha}`, VM pulls (never builds)
- **6 reactor images:** agent1, agent2, agent3, agent4, agent5, agent-user
- **JVM tuning per container:** `mem_limit: 512m`, `-Xmx256m`, `-XX:MaxRAMPercentage=75.0`, 2GB swap on VM
- **Edge:** nginx-proxy-manager, TLS via Let's Encrypt; admin port 81 bound to 127.0.0.1 only
- **Agent5 default profile:** sandbox (simulated fills); `AGENT5_PROFILE=` empty for production

### CI/CD notes
- GitHub Actions skips tests (`-DskipTests`) — E2E/Flyway tests need live Postgres + gitignored application-local.yml
- `upstox-auth` is a separate git repo, NOT in main reactor; needs its own CI pipeline
- GHCR pull on VM: `docker login ghcr.io -u neerajchaplot` with classic PAT (read:packages)

---

## 13. PENDING TASKS (current backlog)

### Session A — Security & Token (agent1 only)
- **#10** — X-API-Key filter: Spring Security filter on agent1 endpoints; validate against env var `X_API_KEY`; return 401 on mismatch
- **#9** — Expired Upstox token: return structured 503 (not stack trace); log event; do NOT retry

### Session C — Scheduling wiring
- **#18** — Pre-Agent-2 candle check: Agent2 (or UI) verifies current 5-min candle is not anomalous; 422 + alert on spike/gap

### Session D — Agent1 data quality
- **#5** — Backtest validation: POST /api/v1/agent1/score with inputs (spot=23412.60, 20EMA=23900, 50EMA=23690, PCR=1.17, FII long ratio=0.11, DII net=684Cr, VIX=18.61, VIX prev=19.43, Gift Nifty +70pts, Marketaux=-0.335); expected: NEUTRAL/WEAK, score≈0.067, confidence=LOW
- **#16** — Highest OI strike (Call Wall / Put Wall): top 3 OI strikes from option chain; spot within 100pts of Call Wall = bearish, Put Wall = bullish; add to Tier 1A or Tier 3

### Session E — Refactors
- **#6** — Move Upstox market data client classes from core-module to agent1; AlertService + UpstoxPositionClient stay in core-module (used by agent3)
- **#12** — Agent1SignalEntity: exists in agent1 and agent2 separately; consolidate
- **#19** — StrategySelector symmetry: WEAK bullish should reroute to `selectNeutral()` (matches WEAK bearish behavior); currently WEAK bullish skips instead of Iron Condor when IV is rich

### Parked indefinitely
- OAuth2 auth (security layer designed to swap without touching business logic)
- Gemini free tier quota fix (#8, not blocking anything)
- EOD P&L sync job (Zerodha → trade_pnl)

---

## 14. HOW TO START A SESSION

```
"Read CLAUDE.md and IMPL_STATUS.md, then work on Session X — [task name]."
```

Claude reads spec + current state without needing conversation history.

For integration testing:
```
"Read CLAUDE.md, IMPL_STATUS.md, and INTEGRATION_TEST_GUIDE.md, then help me run integration tests."
```

---

## 15. QUICK REFERENCE — KEY FILES

| What | Where |
|---|---|
| Spec | `CLAUDE.md` |
| Current build state + task list | `IMPL_STATUS.md` |
| Integration test scenarios | `INTEGRATION_TEST_GUIDE.md` |
| Flyway migrations | `db-migrations/src/main/resources/db/migration/` |
| Shared DTOs / enums | `shared-domain/src/main/java/com/the3Cgrp/zupptrade/shared/` |
| Agent 2 engine layers | `agent2-recommendation/src/main/java/com/the3Cgrp/zupptrade/agent2/engine/` |
| Agent 2 config (hard-gate-enabled) | `agent2-recommendation/src/main/resources/application.yml` |
| TradingConfig (hardGateEnabled field) | `agent2-recommendation/src/main/java/.../agent2/config/TradingConfig.java` |
| RecommendationContext (testing fields) | `agent2-recommendation/src/main/java/.../agent2/engine/RecommendationContext.java` |
| TradeCardDto (skipDecision fields) | `shared-domain/src/main/java/.../shared/dto/TradeCardDto.java` |
| Angular trade model | `zupptrade-ui/src/app/core/models/trade.model.ts` |
| Mobile trade page | `zupptrade-mobile/src/app/pages/trade/trade.page.ts` |
| UI environment / API key | `zupptrade-ui/src/environments/environment.ts` |
| Docker compose | `docker-compose.yml` |

---

*Last updated: 2026-07-12 — Testing mode / hard gate bypass feature implemented across Agent2 backend, zupptrade-ui, and zupptrade-mobile.*
