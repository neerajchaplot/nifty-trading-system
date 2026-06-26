# Integration Testing Guide — Nifty Trading System
## Standalone context for a dedicated testing session

> **How to use this file in a new session:**
> Tell Claude: *"Read CLAUDE.md, IMPL_STATUS.md, and INTEGRATION_TEST_GUIDE.md, then help me run integration tests."*
> This file is the source of truth for test scenarios, DB state, and expected results.

---

## 1. System State (as of 2026-06-21)

### What is built and running
All four agent modules compile and start cleanly against the `zupptrade_dev` schema on Neon DB.

| Module | Port | Status |
|---|---|---|
| agent1-market_analyst | 8081 | Running |
| agent2-recommendation | 8082 | Running |
| agent3-monitor | 8083 | Running |
| agent5-execution | 8085 | Running (sandbox orders via UPSTOX_SANDBOX_TOKEN) |

### Database
- **Neon DB host:** `ep-green-snow-aozeabar.c-2.ap-southeast-1.aws.neon.tech`
- **Database name:** `zupptrade` (the Neon project DB name)
- **Active schema:** `zupptrade_dev` (all tables live here — no tables in `public`)
- **App user:** `zupp_app` (DML only, no DDL)
- **Migration user:** `neondb_owner` (DDL, runs Flyway)
- **Flyway state:** V1–V101 all applied. Next migration = V102.

### Flyway migrations applied
| Version | Content |
|---|---|
| V1 | agent1_signals, user_profiles, trades, reference_data, scoring_audit_log |
| V2 | Default user profile seed |
| V3 | fii_dii_snapshots |
| V4 | trade_code column on trades |
| V5 | trade_ledger (event sourcing) |
| V6 | trade_executions |
| V7 | trade_pnl |
| V8 | monitoring_evaluations |
| V9 | notifications, shedlock |
| V100 | api_tokens |
| V101 | NIFTY_LOT_SIZE = 65 in reference_data |

### Key environment variables (must be set as Windows User env vars)
```
TOKEN_ENCRYPTION_KEY   — AES-256-GCM key for Upstox token decryption
GROQ_API_KEY           — LLM for Agent 1 commentary extraction (llama-3.3-70b-versatile)
UPSTOX_SANDBOX_TOKEN   — for Agent 5 sandbox testing only
```

### How to start any module
```powershell
# From C:\3CGrp\nifty-trading-system\<module-dir>
$env:SPRING_PROFILES_ACTIVE = "local"
mvn spring-boot:run
```

**Startup order:** Start agent2 first (it runs Flyway). Then agent1, agent3, agent5 in any order.

If local Maven dependencies are stale:
```powershell
cd C:\3CGrp\nifty-trading-system
mvn install -pl shared-domain,core-module,ledger-module,db-migrations -DskipTests
```

---

## 2. Step 0 — Pre-flight DB Checks

Run these in Neon SQL console before testing.

```sql
-- 0A. Confirm all tables exist in zupptrade_dev
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'zupptrade_dev'
ORDER BY table_name;
-- Expected: agent1_signals, api_tokens, fii_dii_snapshots, flyway_schema_history,
--           monitoring_evaluations, notifications, reference_data, scoring_audit_log,
--           shedlock, trade_executions, trade_ledger, trade_pnl, trade_pnl, trades, user_profiles

-- 0B. Flyway history — all 11 rows, all success = true
SELECT version, description, success
FROM zupptrade_dev.flyway_schema_history
ORDER BY installed_rank;

-- 0C. Lot size seeded
SELECT value FROM zupptrade_dev.reference_data WHERE key = 'NIFTY_LOT_SIZE';
-- Expected: 65

-- 0D. Default user profile exists (seeded by V2)
SELECT id, user_id, capital, min_pop, max_loss_pct
FROM zupptrade_dev.user_profiles;
-- Expected: 1 row. Note the `id` UUID — you'll need it for Agent 2 tests.

-- 0E. No stale ACTIVE trades from previous tests
SELECT id, trade_code, status, strategy, expiry_date
FROM zupptrade_dev.trades
WHERE status IN ('ACTIVE', 'PENDING_CONFIRM', 'CONFIRMED')
ORDER BY created_at DESC;
```

---

## 3. Agent 1 Tests (port 8081)

### S1.1 — Health check
```
GET http://localhost:8081/api/v1/agent1/health
```
**Expected:** `{ "status": "UP", "last_run": ..., "data_freshness": ... }`

---

### S1.2 — Score with live data (primary test)
```
POST http://localhost:8081/api/v1/agent1/score
Content-Type: application/json

{
  "expiryDate": "2026-06-23",
  "commentary": "Markets opened gap-up today supported by strong FII buying. Nifty above key 200-EMA. Broader market breadth positive with advance-decline ratio of 3:1.",
  "fetchMarketaux": true
}
```
**Expected response shape:**
```json
{
  "id": "uuid",
  "bias": "BULLISH|BEARISH|NEUTRAL",
  "strength": "EXTREME|MILD|WEAK",
  "composite_score": 0.xxxx,
  "confidence": 0.xx,
  "confidence_label": "LOW|MEDIUM|HIGH",
  "vix_level": xx.xx,
  "score_breakdown": { "tier1a": ..., "tier1b": ..., "tier2": ..., "tier3": ..., "tier4": ... },
  "data_gaps": [...]
}
```
**Record the `id` (signal UUID) for Agent 2 tests.**

**Check DB after:**
```sql
SELECT id, bias, strength, composite_score, confidence_label, vix_level, created_at
FROM zupptrade_dev.agent1_signals
ORDER BY created_at DESC LIMIT 3;
```

---

### S1.3 — Score without commentary (Phase 1 — EOD data only)
```
POST http://localhost:8081/api/v1/agent1/score
Content-Type: application/json

{
  "expiryDate": "2026-06-23",
  "commentary": null,
  "fetchMarketaux": true
}
```
**Expected:** Valid signal returned. Tier 4 (commentary) scores 0 (neutral). `data_gaps` should include `COMMENTARY`.

---

### S1.4 — Get latest signal
```
GET http://localhost:8081/api/v1/agent1/latest?expiry_date=2026-06-23
```
**Expected:** Returns the most recent signal. If none for that expiry, returns 404 or empty.

---

### S1.5 — Backtest scenario validation (CLAUDE.md spec test)
This is a fixed regression test. Inputs are mocked via commentary that conveys the scenario.
The expected output is defined in the spec.

```
POST http://localhost:8081/api/v1/agent1/score
Content-Type: application/json

{
  "expiryDate": "2026-06-23",
  "commentary": "Markets are range-bound with mixed signals. FII long ratio at 11%, DII buying 684 Cr. VIX at 18.61, down slightly from yesterday's 19.43. Gift Nifty premium +70 pts. Overall bias unclear with conflicting data points.",
  "fetchMarketaux": false
}
```
Then manually verify via DB (live Upstox data affects the actual score):
```sql
SELECT composite_score, bias, strength, confidence_label, score_breakdown
FROM zupptrade_dev.agent1_signals
ORDER BY created_at DESC LIMIT 1;
```
**Spec expected output:** `Bias=NEUTRAL, Strength=WEAK, Score≈0.067, Confidence=LOW`
Note: exact score will vary with live market data. Confidence=LOW is the key check.

---

## 4. Agent 2 Tests (port 8082)

**Prerequisites:** A valid Agent 1 signal must exist in DB (from S1.2).
**userProfileId:** `90412ca3-1e3f-4c75-9444-ca1ebfd92348`

### S2.1 — Recommend trade
```
POST http://localhost:8082/api/v1/agent2/recommend
Content-Type: application/json

{
  "agent1SignalId": "<uuid from S1.2>",
  "userProfileId": "90412ca3-1e3f-4c75-9444-ca1ebfd92348"
}
```
**Expected response shape:**
```json
{
  "tradeId": "uuid",
  "status": "PENDING_CONFIRM",
  "strategy": "BullPutSpread|BearCallSpread|IronCondor|SKIP",
  "legs": [ ... ],
  "summary": {
    "netPremiumPerUnit": ...,
    "lots": ...,
    "maxProfit": ...,
    "pop": ...,
    "popp": ...
  },
  "gateResults": {
    "gate1Pop": "PASS|FAIL",
    "gate2MaxLoss": "INDICATIVE",
    "gate3PopPoppGap": "PASS|FAIL",
    "gate4MinRoc": "PASS|FAIL"
  },
  "thresholds": { "t1WatchNifty": ..., "t2ReadjustNifty": ..., "t3ExitNifty": ... }
}
```
**If strategy = SKIP:** This is valid when VIX is extreme or bias is too weak. No further Agent 2 tests needed for that signal — re-run S1.2 at a different time or use a different commentary.

**Record the `tradeId` for S2.2 and S2.3.**

**Check DB:**
```sql
SELECT id, trade_code, status, strategy, expiry_date,
       summary->>'pop' AS pop,
       summary->>'lots' AS lots
FROM zupptrade_dev.trades
WHERE status = 'PENDING_CONFIRM'
ORDER BY created_at DESC LIMIT 1;
```

---

### S2.2 — Confirm trade
```
POST http://localhost:8082/api/v1/agent2/confirm
Content-Type: application/json

{
  "tradeId": "<uuid from S2.1>",
  "action": "CONFIRM",
  "overrideLots": null
}
```
**Expected:** `{ "tradeId": "...", "status": "CONFIRMED", "executionOrder": { ... } }`

**Check DB:**
```sql
SELECT id, status, confirmed_at FROM zupptrade_dev.trades WHERE id = '<tradeId>';
SELECT event_type, occurred_at FROM zupptrade_dev.trade_ledger
WHERE trade_id = '<tradeId>' ORDER BY sequence_number;
-- Expected ledger events: TRADE_PENDING → TRADE_APPROVED
```

---

### S2.3 — Reject trade (separate test run)
```
POST http://localhost:8082/api/v1/agent2/confirm
Content-Type: application/json

{
  "tradeId": "<uuid of a different PENDING_CONFIRM trade>",
  "action": "REJECT",
  "overrideLots": null
}
```
**Expected:** `{ "status": "REJECTED" }`

---

### S2.4 — Get monitor config (called after Agent 5 fills)
```
GET http://localhost:8082/api/v1/agent2/monitor-config/<tradeId>
X-Short-Fill-Price: 68.40
X-Long-Fill-Price: 45.20
```
**Expected:** Full monitor config JSON including greeks at entry and T1/T2/T3 thresholds.
**Note:** Fill price headers are required. Use realistic LTP values from the legs in S2.1 response.

---

## 5. Agent 3 Tests (port 8083)

### S3.1 — Active trades (empty state)
```
GET http://localhost:8083/api/v1/agent3/active-trades
```
**Expected:** Empty array `[]` if no ACTIVE trades.

---

### S3.2 — Seed a HOLD scenario trade

Run this SQL to seed an ACTIVE trade that Agent 3 should evaluate as HOLD.
Replace `{USER_PROFILE_ID}` with the UUID from Step 0D.
Replace `{SIGNAL_ID}` with a signal UUID from Step 0.
Replace `{EXPIRY_DATE}` with `2026-06-23` (current week's expiry — Monday this week).
Replace `{SHORT_STRIKE}` with a strike ~200 pts below current Nifty spot (spot is comfortably above).

```sql
INSERT INTO zupptrade_dev.trades (
    id, agent1_signal_id, user_profile_id, status, strategy,
    expiry_date, trade_code, legs, summary, market_context,
    monitor_config, entry_fills, confirmed_at
) VALUES (
    gen_random_uuid(),
    (SELECT id FROM zupptrade_dev.agent1_signals ORDER BY created_at DESC LIMIT 1),
    (SELECT id FROM zupptrade_dev.user_profiles LIMIT 1),
    'ACTIVE',
    'BullPutSpread',
    '{EXPIRY_DATE}',
    'TRD-20260621-0001',
    '[
        {"action":"SELL","strike":{SHORT_STRIKE},"type":"PE","ltp":68.40,"iv":17.2,"delta":-0.169,"theta":-12.4,"vega":18.6,"pop":0.13},
        {"action":"BUY","strike":{SHORT_STRIKE}-100,"type":"PE","ltp":45.20,"iv":17.8,"delta":-0.142,"theta":-10.1,"vega":15.9,"pop":0.08}
    ]'::jsonb,
    '{"netPremiumPerUnit":23.20,"spreadWidth":100,"lots":5,"lotSize":65,"maxProfit":7540,"maxLossTheoretical":24960,"realExpectedLoss":12480,"pop":83.1,"popp":86.3,"popPoppGap":3.2}'::jsonb,
    '{"spot":24150.00,"vix":15.5,"iv":17.2,"bias":"BULLISH","strength":"MILD"}'::jsonb,
    '{
        "tradeId": null,
        "shortStrike": {SHORT_STRIKE},
        "longStrike": {SHORT_STRIKE}-100,
        "spreadType": "CREDIT",
        "thresholds": {
            "t1WatchNifty": {SHORT_STRIKE}+150,
            "t2ReadjustNifty": {SHORT_STRIKE}+75,
            "t3ExitNifty": {SHORT_STRIKE},
            "t2PnlLoss": 6240,
            "t3PnlLoss": 12480
        },
        "entryShortFillPrice": 68.40,
        "entryLongFillPrice": 45.20,
        "dteAtEntry": 3
    }'::jsonb,
    '{"leg1":{"action":"SELL","fillPrice":68.40},"leg2":{"action":"BUY","fillPrice":45.20}}'::jsonb,
    NOW()
);
```

After inserting, note the generated `id`:
```sql
SELECT id, trade_code, status FROM zupptrade_dev.trades
WHERE trade_code = 'TRD-20260621-0001';
```

---

### S3.3 — Evaluate the seeded trade (expect HOLD)
```
POST http://localhost:8083/api/v1/agent3/evaluate/<trade_id from S3.2>
```
**Expected:** `{ "action": "HOLD", "reason": "...", "current_pnl": ... }`
Current Nifty spot is well above T1 level → HOLD.

**Check DB:**
```sql
SELECT trade_id, action, spot_price, current_pop, mark_to_market_pnl, evaluated_at
FROM zupptrade_dev.monitoring_evaluations
WHERE trade_id = '<tradeId>'
ORDER BY evaluated_at DESC LIMIT 3;
```

---

### S3.4 — Active trades shows the seeded trade
```
GET http://localhost:8083/api/v1/agent3/active-trades
```
**Expected:** Array containing the seeded trade with `monitorConfig` populated and latest evaluation snapshot.

---

### S3.5 — GAP 1 test (monitor_config auto-seeding)
Seed a trade with `monitor_config = NULL` but `entry_fills` populated.
Agent 3 scheduler should detect this and auto-call Agent 2 `/monitor-config` to seed it.

```sql
INSERT INTO zupptrade_dev.trades (
    id, agent1_signal_id, user_profile_id, status, strategy,
    expiry_date, trade_code, legs, summary, market_context,
    monitor_config, entry_fills, confirmed_at
) VALUES (
    gen_random_uuid(),
    (SELECT id FROM zupptrade_dev.agent1_signals ORDER BY created_at DESC LIMIT 1),
    (SELECT id FROM zupptrade_dev.user_profiles LIMIT 1),
    'ACTIVE',
    'BullPutSpread',
    '{EXPIRY_DATE}',
    'TRD-20260621-0002',
    '[
        {"action":"SELL","strike":23500,"type":"PE","ltp":55.00,"iv":16.5,"delta":-0.15,"pop":0.12},
        {"action":"BUY","strike":23400,"type":"PE","ltp":35.00,"iv":17.0,"delta":-0.11,"pop":0.07}
    ]'::jsonb,
    '{"netPremiumPerUnit":20.00,"lots":3,"lotSize":65}'::jsonb,
    '{"spot":24100.00,"vix":16.0}'::jsonb,
    NULL,
    '{"leg1":{"action":"SELL","fillPrice":55.00},"leg2":{"action":"BUY","fillPrice":35.00}}'::jsonb,
    NOW()
);
```

Wait for the next Agent 3 scheduler cycle (runs every 5 min during market hours).

**Verify auto-seeding:**
```sql
SELECT id, trade_code,
       monitor_config IS NOT NULL AS config_seeded,
       entry_fills IS NOT NULL AS fills_present
FROM zupptrade_dev.trades WHERE trade_code = 'TRD-20260621-0002';
-- Expected: config_seeded = true after one scheduler cycle
```

---

## 6. Agent 5 Tests (port 8085)

**Prerequisite:** `UPSTOX_SANDBOX_TOKEN` must be set as Windows User env var.
Start agent5 with sandbox profile:
```powershell
cd agent5-execution
$env:SPRING_PROFILES_ACTIVE = "local,sandbox"
mvn spring-boot:run
```

### S5.1 — Execute a sandbox trade

Use the `executionOrder` from S2.2 (Agent 2 confirm response).

```
POST http://localhost:8085/api/v1/agent5/execute
Content-Type: application/json

{
  "tradeId": "<uuid from S2.2>",
  "strategy": "BullPutSpread",
  "legs": [
    { "action": "SELL", "strike": 23750, "type": "PE", "ltp": 68.40, "lots": 5 },
    { "action": "BUY",  "strike": 23650, "type": "PE", "ltp": 45.20, "lots": 5 }
  ],
  "lotSize": 65,
  "lots": 5
}
```
**Expected:** Order placed on Upstox sandbox. Response includes fill prices or order IDs.

**Check DB:**
```sql
SELECT trade_id, execution_type, broker_status, filled_lots, requested_lots,
       slippage_amount, placed_at, executed_at
FROM zupptrade_dev.trade_executions
WHERE trade_id = '<tradeId>'
ORDER BY placed_at DESC;
```

---

### S5.2 — Exit a sandbox trade
```
POST http://localhost:8085/api/v1/agent5/exit/<tradeId>
```
**Expected:** Exit (reverse) orders placed on sandbox. Trade status → CLOSED in DB.

---

### S5.3 — Margin check behaviour
Verify that if requested lots exceed available margin, Agent 5 rejects before placing any order.
Use an unrealistically large lot count in S5.1 to trigger this (e.g., lots = 500).

---

## 7. End-to-End Flow Test

This exercises the full path: A1 → A2 → A5 → A3.

**All four agents must be running simultaneously.**

```
Step 1: POST /api/v1/agent1/score          → record signal_id
Step 2: POST /api/v1/agent2/recommend      → record trade_id, check gates all PASS
Step 3: POST /api/v1/agent2/confirm        → action=CONFIRM, record execution_order
Step 4: POST /api/v1/agent5/execute        → sandbox fill, record fill prices
Step 5: GET  /api/v1/agent2/monitor-config → pass fill prices as X-Short-Fill-Price / X-Long-Fill-Price headers
Step 6: GET  /api/v1/agent3/active-trades  → trade should appear with monitorConfig
Step 7: POST /api/v1/agent3/evaluate/<id>  → expect HOLD (spot far from short strike)
```

**Full verification SQL after E2E:**
```sql
-- Trade lifecycle
SELECT t.trade_code, t.status, t.strategy, t.confirmed_at,
       tl.event_type, tl.occurred_at
FROM zupptrade_dev.trades t
JOIN zupptrade_dev.trade_ledger tl ON tl.trade_id = t.id
WHERE t.trade_code LIKE 'TRD-%'
ORDER BY t.created_at DESC, tl.sequence_number;

-- Agent 3 evaluations for the trade
SELECT action, spot_price, current_pop, mark_to_market_pnl, threshold_hit, evaluated_at
FROM zupptrade_dev.monitoring_evaluations
WHERE trade_id = '<tradeId>'
ORDER BY evaluated_at DESC;

-- Notifications written
SELECT level, message, created_at
FROM zupptrade_dev.notifications
ORDER BY created_at DESC LIMIT 10;
```

---

## 8. Known Issues & Gotchas

### Architecture
- **Agent 3 scheduler** runs every 5 min only during market hours (9:15–15:30 IST, Mon–Fri). Outside these hours, call `POST /api/v1/agent3/evaluate/{tradeId}` directly.
- **Upstox token:** loaded from `api_tokens` table by `ApiTokenDbLoader` at startup. If token is missing or stale, Upstox API calls will return 401. Run `upstox-auth` module to refresh:
  ```powershell
  cd upstox-auth
  mvn spring-boot:run
  ```
- **Agent 5 sandbox vs production:** sandbox profile routes orders to `api-sandbox.upstox.com` but margin checks still hit `api.upstox.com` (real API). UPSTOX_SANDBOX_TOKEN is separate from the production token.

### DB
- If a Flyway migration fails on restart, check `zupptrade_dev.flyway_schema_history` for failed rows (`success = false`) and repair or delete them before retrying.
- **Next migration is V102.** Never add V10–V99 — they fall before V100 which is already applied.

### Agent 2 SKIP result
If Agent 2 returns `strategy = SKIP`, this means:
- VIX is in Extreme regime (> 24), OR
- Bias is too weak (Weak strength regardless of direction), OR
- IV is cheap (IV/HV ratio < 0.85)
Run Agent 1 again at a different time or adjust commentary to reflect a clearer directional view.

### Auto-configuration (resolved)
Both `AlertAutoConfiguration` and `LedgerAutoConfiguration` in `core-module` and `ledger-module` were changed from `@ConditionalOnBean` to `@ConditionalOnClass` to fix ordering issues in Spring Boot 4 auto-configuration. This is already committed — no action needed.

### Schema convention
All tables are in `zupptrade_dev`. No tables should exist in `public`. The JDBC URL includes `currentSchema=zupptrade_dev` and HikariCP `connection-init-sql` sets `search_path` as a belt-and-suspenders guarantee.

---

## 9. Test Data Cleanup

After testing, clean up seeded trades:
```sql
-- Remove test monitoring evaluations
DELETE FROM zupptrade_dev.monitoring_evaluations
WHERE trade_id IN (
    SELECT id FROM zupptrade_dev.trades
    WHERE trade_code IN ('TRD-20260621-0001', 'TRD-20260621-0002')
);

-- Remove test ledger entries
DELETE FROM zupptrade_dev.trade_ledger
WHERE trade_id IN (
    SELECT id FROM zupptrade_dev.trades
    WHERE trade_code LIKE 'TRD-20260621-%'
);

-- Remove test trade executions
DELETE FROM zupptrade_dev.trade_executions
WHERE trade_id IN (
    SELECT id FROM zupptrade_dev.trades
    WHERE trade_code LIKE 'TRD-20260621-%'
);

-- Remove test trades
DELETE FROM zupptrade_dev.trades
WHERE trade_code LIKE 'TRD-20260621-%';

-- Optionally remove test Agent 1 signals
DELETE FROM zupptrade_dev.agent1_signals
WHERE created_at::date = '2026-06-21';
```

---

*Last updated: 2026-06-21 — All modules up on zupptrade_dev schema. V101 applied.*
