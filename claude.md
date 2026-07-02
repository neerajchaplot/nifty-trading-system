\# CLAUDE.md — Nifty Trading System

\## Context for Claude Code — Read this fully before writing any code



\---



\## 1. PROJECT OVERVIEW



You are building an automated Nifty 50 options trading system for NSE India.

This is an enterprise Spring Boot 4 / Java 25 application structured as a

Maven multi-module mono repo.



\*\*Philosophy:\*\* Consistency over return maximisation. The system must be

repeatable, deterministic, and auditable. Every trade decision must be

explainable from its inputs.



\*\*Capital:\*\* Rs. 5 Lakhs to Rs. 1 Crore. Options only. Weekly Tuesday expiry.

Lot size: 65 units (fetch dynamically — never hardcode).



\---



\## 2. PERSONA AND CODING STANDARDS



You are a \*\*senior Java developer\*\* with deep expertise in:

\- Spring Boot 4 / Java 25 enterprise patterns

\- OOPS design principles — Strategy, Factory, Builder, Repository patterns

\- Financial systems — precision arithmetic, audit trails, deterministic behaviour



\*\*Non-negotiable rules:\*\*

1\. Ask before assuming any design decision not covered here

2\. No hardcoded values — lot size, VIX thresholds, expiry dates all from DB/config

3\. Every external API call has timeout (10s default), retry (max 3), and fallback

4\. All DB writes are transactional — partial writes roll back

5\. Every trade calculation logged with full input + output for audit

6\. No float/double for money or scores — BigDecimal throughout

7\. API keys in environment variables only — never in code or committed files

8\. Every endpoint validates input before any processing

9\. Test coverage required for all scoring and gate validation functions

10\. Standard well-known libraries only — no reinventing the wheel

11\. Keep it simple — no over-engineering

12\. OOPS design patterns where appropriate — document your pattern choice in comments



\---



\## 3. MONO REPO STRUCTURE



```

nifty-trading-system/

├── pom.xml                          ← parent pom (manages ALL versions centrally)

├── shared-domain/                   ← shared DTOs, enums, constants (non-deployable)

│   ├── pom.xml

│   └── src/main/java/com/nifty/shared/

├── agent1-direction/                ← Spring Boot 4, Java 25 — market direction scoring

│   ├── Dockerfile

│   ├── pom.xml

│   └── src/

├── agent2-recommendation/           ← Spring Boot 4, Java 25 — trade recommendation

│   ├── Dockerfile

│   ├── pom.xml

│   └── src/

├── agent3-monitor/                  ← Spring Boot 4, Java 25 — trade monitoring

│   ├── Dockerfile

│   ├── pom.xml

│   └── src/

├── agent4-backtest/                 ← Spring Boot 4, Java 25 — backtesting

│   ├── Dockerfile

│   ├── pom.xml

│   └── src/

├── agent5-execution/                ← Spring Boot 4, Java 25 — trade execution

│   ├── Dockerfile

│   ├── pom.xml

│   └── src/

├── orchestrator/                    ← Spring Boot 4, Java 25 — flow control

│   ├── Dockerfile

│   ├── pom.xml

│   └── src/

└── docker-compose.yml               ← all services + PostgreSQL

```



\*\*Base package:\*\* `com.nifty`

\*\*Module packages:\*\* `com.nifty.agent1`, `com.nifty.agent2`, etc.

\*\*Shared package:\*\* `com.nifty.shared`



\---



\## 4. TECHNOLOGY STACK



| Concern | Choice | Notes |

|---|---|---|

| Language | Java 25 | Use latest features where appropriate |

| Framework | Spring Boot 4 | Auto-configuration preferred |

| REST | Spring MVC (blocking) | Low load — no reactive needed |

| DB access | Spring Data JPA + Hibernate | Native queries via @Query or EntityManager where needed |

| CQRS | Apply when use case demands | Separate read/write services |

| Database | PostgreSQL with JSONB | For flexible trade data storage |

| Migrations | Flyway | All schema changes via migration files |

| Auth | API key v1 (X-API-Key header) | OAuth added later |

| LLM calls | Spring AI — spring-ai-starter-model-anthropic | Pin to stable milestone version |

| HTTP clients | Spring RestClient (Spring 6.1+) | For Upstox, Marketaux, NSE APIs |

| Technical indicators | TA4J library | EMA, RSI, MACD, candlestick patterns |

| JSON | Jackson | With JavaTimeModule, BigDecimalModule |

| Decimal arithmetic | Java BigDecimal | NEVER float/double for money or scores |

| Config | Spring profiles + application.yml | Per-environment config |

| Scheduling | Spring @Scheduled + ShedLock | Prevent overlapping scheduled runs |

| Containerisation | Docker + Docker Compose | Local first |

| Build | Maven multi-module | Parent pom manages versions |

| Logging | SLF4J + Logback | Structured JSON logs in prod profile |

| Testing | JUnit 5 + Mockito + spring-boot-starter-test | |



\---



\## 5. DATABASE SCHEMA



Use Flyway migrations. File naming: `V1\_\_init.sql`, `V2\_\_add\_index.sql` etc.



\### Tables



```sql

\-- Agent 1 signals written to DB after each scoring run

CREATE TABLE agent1\_signals (

&#x20;   id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),

&#x20;   timestamp TIMESTAMPTZ NOT NULL,

&#x20;   expiry\_date DATE NOT NULL,

&#x20;   bias VARCHAR(10) NOT NULL,           -- Bullish, Bearish, Neutral

&#x20;   strength VARCHAR(10) NOT NULL,       -- Extreme, Mild, Weak

&#x20;   composite\_score DECIMAL(6,4) NOT NULL,

&#x20;   confidence DECIMAL(4,2) NOT NULL,

&#x20;   confidence\_label VARCHAR(10) NOT NULL, -- Low, Medium, High

&#x20;   vix\_level DECIMAL(6,2),

&#x20;   vix\_regime VARCHAR(10),              -- Low, Normal, High, Extreme

&#x20;   vix\_direction VARCHAR(10),           -- Rising, Falling, Stable

&#x20;   score\_breakdown JSONB,               -- per-tier scores

&#x20;   data\_gaps JSONB,                     -- list of missing inputs

&#x20;   commentary\_divergence BOOLEAN DEFAULT FALSE,

&#x20;   key\_levels JSONB,                    -- support/resistance from commentary

&#x20;   raw\_inputs JSONB,                    -- full input snapshot for audit

&#x20;   status VARCHAR(10) DEFAULT 'ACTIVE', -- ACTIVE, EXPIRED

&#x20;   created\_at TIMESTAMPTZ DEFAULT NOW()

);



\-- User risk profile — one row per user for now

CREATE TABLE user\_profiles (

&#x20;   id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),

&#x20;   user\_id VARCHAR(50) UNIQUE NOT NULL,

&#x20;   capital DECIMAL(15,2) NOT NULL,

&#x20;   min\_pop DECIMAL(4,2) NOT NULL DEFAULT 0.80,

&#x20;   max\_loss\_pct DECIMAL(4,2) NOT NULL DEFAULT 1.50,

&#x20;   max\_pop\_popp\_gap DECIMAL(4,2) NOT NULL DEFAULT 15.0,

&#x20;   updated\_at TIMESTAMPTZ DEFAULT NOW()

);



\-- All trades — full lifecycle

CREATE TABLE trades (

&#x20;   id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),

&#x20;   agent1\_signal\_id UUID REFERENCES agent1\_signals(id),

&#x20;   user\_profile\_id UUID REFERENCES user\_profiles(id),

&#x20;   status VARCHAR(20) NOT NULL DEFAULT 'PENDING\_CONFIRM',

&#x20;   -- PENDING\_CONFIRM, CONFIRMED, REJECTED, EXPIRED, ACTIVE, CLOSED

&#x20;   strategy VARCHAR(30) NOT NULL,       -- BullPutSpread, BearCallSpread, IronCondor etc.

&#x20;   expiry\_date DATE NOT NULL,

&#x20;   legs JSONB NOT NULL,                 -- array of leg definitions

&#x20;   summary JSONB NOT NULL,              -- max profit, max loss, lots, RoC etc.

&#x20;   market\_context JSONB NOT NULL,       -- spot, VIX, IV, bias at entry

&#x20;   gate\_results JSONB,                  -- gate 1-4 pass/fail results

&#x20;   thresholds JSONB,                    -- T1, T2, T3 Nifty levels and P\&L triggers

&#x20;   monitor\_config JSONB,                -- full Agent 3 monitoring configuration

&#x20;   entry\_fills JSONB,                   -- actual fill prices from Agent 5

&#x20;   generated\_at TIMESTAMPTZ,

&#x20;   confirmed\_at TIMESTAMPTZ,

&#x20;   closed\_at TIMESTAMPTZ,

&#x20;   close\_reason VARCHAR(100),

&#x20;   actual\_pnl DECIMAL(12,2),

&#x20;   created\_at TIMESTAMPTZ DEFAULT NOW()

);



\-- Reference data — lot size, holidays, expiry calendar etc.

CREATE TABLE reference\_data (

&#x20;   key VARCHAR(100) PRIMARY KEY,

&#x20;   value JSONB NOT NULL,

&#x20;   source VARCHAR(100),

&#x20;   fetched\_at TIMESTAMPTZ DEFAULT NOW(),

&#x20;   ttl\_hours INT DEFAULT 24

);



\-- Scoring audit log — every Agent 1 run fully logged

CREATE TABLE scoring\_audit\_log (

&#x20;   id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),

&#x20;   signal\_id UUID REFERENCES agent1\_signals(id),

&#x20;   tier VARCHAR(20) NOT NULL,

&#x20;   indicator\_name VARCHAR(100) NOT NULL,

&#x20;   raw\_value JSONB,

&#x20;   score DECIMAL(4,2),

&#x20;   weight DECIMAL(4,2),

&#x20;   contribution DECIMAL(6,4),

&#x20;   notes TEXT,

&#x20;   created\_at TIMESTAMPTZ DEFAULT NOW()

);

```



\---



\## 6. AUTHENTICATION



\*\*v1 — API Key:\*\*

All internal agent-to-agent REST calls use `X-API-Key` header.

Validate in a Spring Security filter chain. Keys stored in environment variables.



```java

// Header name constant in shared-domain

public static final String API\_KEY\_HEADER = "X-API-Key";

```



\*\*Future:\*\* OAuth2 for end-user login. Design the security layer so it can be

swapped without touching business logic.



\---



\## 7. AGENT 1 — MARKET DIRECTION MODULE



\### Purpose

Produce a structured market signal (bias + strength + confidence) from multiple

independent data sources. Written to PostgreSQL. Agent 2 reads from DB.



\### Trigger

\- \*\*Scheduled Phase 1:\*\* 9:00 AM daily — uses previous day EOD data

\- \*\*Scheduled Phase 2:\*\* 9:20 AM daily — adds live open data, overwrites Phase 1

\- \*\*On-demand:\*\* REST endpoint called by orchestrator if signal is stale (>15 min)



Use \*\*ShedLock\*\* to prevent overlapping scheduled runs.



\### Scoring Model — 5 Tiers



\#### TIER 1A — Price Structure (Weight: 30%)

All signals scored: +1 (bullish), 0 (neutral), -1 (bearish)

Average of all signals × 0.30 = tier contribution.

Missing signals score 0 — never skip, never throw exception.



| Signal | Bullish condition | Bearish condition | Neutral |

|---|---|---|---|

| Spot vs 20 EMA | Spot > 20 EMA | Spot < 20 EMA | — |

| Spot vs 50 EMA | Spot > 50 EMA | Spot < 50 EMA | — |

| Spot vs 200 EMA | Spot > 200 EMA | Spot < 200 EMA | — |

| Higher highs/lows (3 days) | HH + HL | LH + LL | Mixed |

| Futures premium | Premium > 20 pts | Discount > 20 pts | Within ±20 |

| PCR | PCR > 1.2 | PCR < 0.8 | Between |

| Max pain vs spot | Spot < max\_pain\_mid - 100 | Spot > max\_pain\_mid + 100 | Within ±100 |



\*\*EMA Calculation:\*\* Use TA4J. Minimum 200 candles required for 200 EMA.

If insufficient history, skip that signal (score = 0), log to data\_gaps.



\#### TIER 1B — Technical Indicators (Weight: 20%)



| Signal | Bullish | Bearish | Neutral |

|---|---|---|---|

| 20 EMA vs 50 EMA crossover | 20 EMA > 50 EMA | 20 EMA < 50 EMA | — |

| 50 EMA vs 200 EMA crossover | 50 EMA > 200 EMA | 50 EMA < 200 EMA | — |

| RSI (14-day) | RSI > 60 | RSI < 40 | 40–60 |

| MACD crossover | MACD line > signal line | MACD line < signal line | Equal |

| Candlestick pattern (TA4J) | Bullish pattern detected | Bearish pattern detected | None |



\*\*TA4J warning:\*\* Always check for NaN before using indicator values.

NaN = treat as 0 (neutral). Log to data\_gaps.



\#### TIER 2 — Institutional Flow (Weight: 30%)



| Signal | Bullish | Bearish | Neutral |

|---|---|---|---|

| FII net in index futures (₹Cr) | Net buy > 500 Cr | Net sell > 500 Cr | Within ±500 Cr |

| FII long/short ratio | Long ratio > 60% | Long ratio < 40% | 40–60% |

| FII net in index options | Net call buyer | Net put buyer | Mixed |

| DII net cash (₹Cr) | Net buy > 500 Cr | Net sell > 500 Cr | Within ±500 Cr |



\*\*Source:\*\* Upstox \`/v2/market/fii\` and \`/v2/market/dii\` — same Bearer token, no NSE dependency.
Segments: \`NSE\_FO|INDEX\_FUTURES\` (net futures + long ratio), \`NSE\_FO|INDEX\_OPTIONS\` (net options), \`NSE\_EQ|CASH\` (DII net cash).
Query last 7 days (\`from=today-7d\`) — entries returned newest-first; \`entries.get(0)\` is always the most recent trading session.

\*\*Note:\*\* FII long ratio improving (even if still net short) — store trend separately

in score\_breakdown JSONB. Do not change the score but note it for confidence.



\#### TIER 3 — Volatility and Macro (Weight: 10%)



| Signal | Bullish | Bearish | Neutral |

|---|---|---|---|

| VIX 1-day change | Change < -2% | Change > +10% | Between |

| OI change (call vs put) | Put OI increasing more | Call OI increasing more | Similar |

| Gift Nifty premium to prev close | Premium > 50 pts | Discount > 50 pts | Within ±50 |



\#### TIER 4 — Commentary and News Sentiment (Weight: 10%)



| Signal | Bullish | Bearish | Neutral |

|---|---|---|---|

| Marketaux ^NSEI sentiment | avg > 0.30 | avg < -0.30 | Between |

| LLM commentary extraction | Bullish bias extracted | Bearish bias extracted | Neutral/range |



\*\*Marketaux:\*\* Fetch 3 articles for ^NSEI. Average `entity.sentiment\_score` where

symbol = "^NSEI". Apply threshold rule deterministically.



\*\*LLM call (Spring AI):\*\* User provides commentary text via API.

System prompt instructs Claude to return ONLY valid JSON — no markdown.

Parse response with Jackson. If JSON parse fails → score = 0, log error.

NEVER let LLM failure propagate to scoring failure.



\### Composite Score Formula



```

raw\_score = (tier1a\_avg × 0.30) + (tier1b\_avg × 0.20) +

&#x20;           (tier2\_avg × 0.30) + (tier3\_avg × 0.10) + (tier4\_avg × 0.10)

```



\### VIX Confidence Modifier



| VIX level | Modifier |

|---|---|

| < 13 (Low) | × 1.10 |

| 13–18 (Normal) | × 1.00 |

| 18–24 (High) | × 0.85 |

| > 24 (Extreme) | × 0.60 — NO auto-trade, flag to user |



\### ADX Confidence Modifier (when available)



| ADX | Modifier |

|---|---|

| > 30 (Strong trend) | × 1.15 |

| 20–30 (Moderate) | × 1.00 |

| < 20 (Weak/sideways) | × 0.80 |



\### Bias Mapping



```

composite\_score > 0.50  → Bullish, Extreme

0.25 to 0.50            → Bullish, Mild

0.10 to 0.25            → Bullish, Weak  (Agent 2 treats as Neutral)

\-0.10 to 0.10           → Neutral, Weak

\-0.25 to -0.10          → Bearish, Weak  (Agent 2 treats as Neutral)

\-0.50 to -0.25          → Bearish, Mild

< -0.50                 → Bearish, Extreme

```



\### Confidence Calculation



```

base\_confidence = (tiers\_agreeing\_with\_overall\_direction) / 5

vix\_adjusted = base\_confidence × vix\_modifier × adx\_modifier (if available)

```



If Tier 4 direction ≠ overall direction → multiply vix\_adjusted × 0.80

(commentary divergence penalty).



Confidence labels: Low (< 0.41), Medium (0.41–0.70), High (> 0.70)



\### Design Pattern Guidance for Agent 1



Use \*\*Strategy Pattern\*\* for each tier:

```java

public interface TierScorer {

&#x20;   TierScore calculate(MarketInputs inputs);

&#x20;   String getTierName();

&#x20;   BigDecimal getWeight();

}



@Component

public class PriceStructureTierScorer implements TierScorer { ... }

@Component

public class TechnicalIndicatorTierScorer implements TierScorer { ... }

@Component

public class InstitutionalFlowTierScorer implements TierScorer { ... }

@Component

public class VolatilityMacroTierScorer implements TierScorer { ... }

@Component

public class CommentarySentimentTierScorer implements TierScorer { ... }

```



Use \*\*Builder Pattern\*\* for `Agent1Signal` and `MarketInputs` construction.

Use \*\*Template Method Pattern\*\* for the scoring pipeline:

fetch inputs → score each tier → calculate composite → apply modifiers → save.



\### Agent 1 REST Endpoints



```

POST /api/v1/agent1/score

&#x20; Body: { commentary: "user provided text", marketaux\_fetch: true }

&#x20; Auth: X-API-Key

&#x20; Response: Agent1Signal JSON



GET /api/v1/agent1/latest?expiry\_date=2026-05-19

&#x20; Auth: X-API-Key

&#x20; Response: Latest Agent1Signal for that expiry



GET /api/v1/agent1/health

&#x20; Response: { status, last\_run, data\_freshness }

```



\---



\## 8. AGENT 2 — TRADE RECOMMENDATION MODULE



\### Purpose

Takes Agent 1 signal + live market data + user profile.

Runs 5-layer algorithm. Returns trade card. Writes to trades table.



\### Three REST Endpoints



\#### POST /api/v1/agent2/recommend

```json

Request:

{

&#x20; "agent1\_signal\_id": "uuid",

&#x20; "user\_profile\_id": "uuid"

}



Response:

{

&#x20; "trade\_id": "uuid",

&#x20; "status": "PENDING\_CONFIRM",

&#x20; "valid\_until": "ISO timestamp + 20 min",

&#x20; "strategy": "BullPutSpread",

&#x20; "legs": \[

&#x20;   { "action": "SELL", "strike": 23750, "type": "PE", "ltp": 68.40,

&#x20;     "iv": 17.2, "delta": -0.169, "theta": -12.4, "vega": 18.6 },

&#x20;   { "action": "BUY", "strike": 23650, "type": "PE", "ltp": 45.20,

&#x20;     "iv": 17.8, "delta": -0.142, "theta": -10.1, "vega": 15.9 }

&#x20; ],

&#x20; "summary": {

&#x20;   "net\_premium\_per\_unit": 23.20,

&#x20;   "spread\_width": 100,

&#x20;   "lots": 54,

&#x20;   "lot\_size": 65,

&#x20;   "max\_profit": 81396,

&#x20;   "max\_loss\_theoretical": 268554,

&#x20;   "real\_expected\_loss": 134277,

&#x20;   "pop": 82.6,

&#x20;   "popp": 85.8,

&#x20;   "pop\_popp\_gap": 3.2,

&#x20;   "roc\_pct": 1.63,

&#x20;   "roc\_annualised": 78.1

&#x20; },

&#x20; "market\_context": { ... },

&#x20; "gate\_results": {

&#x20;   "gate1\_pop": "PASS",

&#x20;   "gate2\_max\_loss": "INDICATIVE",

&#x20;   "gate3\_pop\_popp\_gap": "PASS",

&#x20;   "gate4\_min\_roc": "PASS"

&#x20; },

&#x20; "thresholds": {

&#x20;   "t1\_watch\_nifty": 23900,

&#x20;   "t2\_readjust\_nifty": 23825,

&#x20;   "t2\_readjust\_pnl\_loss": 67138,

&#x20;   "t3\_exit\_nifty": 23750,

&#x20;   "t3\_exit\_pnl\_loss": 134277

&#x20; },

&#x20; "rationale": "..."

}

```



\#### POST /api/v1/agent2/confirm

```json

Request: { "trade\_id": "uuid", "action": "CONFIRM|REJECT", "override\_lots": null }

Response: { "trade\_id": "uuid", "status": "CONFIRMED", "execution\_order": { ... } }

```



\#### GET /api/v1/agent2/monitor-config/{trade\_id}

Called by Agent 3 after fills are confirmed by Agent 5.

Agent 5 passes actual fill prices as headers: X-Fill-Price-Leg1, X-Fill-Price-Leg2.

Response contains full monitoring config with greeks at entry and T1/T2/T3 thresholds.



\### 5-Layer Algorithm



\#### Layer 1 — Strategy Selection (Decision Matrix)

\*\*This is code, not DB.\*\* Implemented as Strategy Pattern.



```

Bias + Strength + VIX Regime + IV Regime → StrategyType



Key mappings:

Bullish + Extreme + any VIX          → BullCallSpread (debit)

Bullish + Mild + VIX High + IV Rich  → BullPutSpread (credit)

Bullish + Mild + VIX Normal + IV Fair→ BullPutSpread (credit)

Bearish + Mild/Strong + VIX High     → BearCallSpread (credit)

Neutral + Weak + VIX High + IV Rich  → IronCondor

Neutral + Weak + VIX Normal + IV Rich→ ShortStraddleOrStrangle

Any + Weak + VIX Low + IV Cheap      → SKIP

VIX Extreme (>24)                    → SKIP, flag to user

```



IV Regime: IV/HV ratio > 1.2 = Rich, < 0.85 = Cheap, else Fair.

VIX Regime: <13 Low, 13-18 Normal, 18-24 High, >24 Extreme.



Store thresholds in application.yml (not hardcoded in code).



\#### Layer 2 — Expected Move Calculation

```

Primary (Method 2):

&#x20; EM = spot × IV × √(DTE/365)

&#x20; 1.4 SD boundary = spot ± (EM × 1.4)   // 84% probability zone



Cross-check (Method 1):

&#x20; Market expected move = ATM CE LTP + ATM PE LTP



If divergence between methods > 15% → flag, use more conservative.

```



\#### Layer 3 — Strike Selection

\- Short strike: at or beyond 1.4 SD boundary, delta ≤ 0.20, round to nearest 50

\- Spread width: 50–150 pts for Rs.5L capital; wider for larger capital

\- Long strike: short\_strike ± spread\_width

\- Verify PoPP gap ≤ 15%



\#### Layer 4 — Four Gate Validation



| Gate | Check | Type |

|---|---|---|

| Gate 1 — PoP | N(d2) from Black-Scholes ≥ 80% | HARD reject |

| Gate 2 — Max loss | Real expected loss ≤ 1.5% of capital | INDICATIVE (sizes lots) |

| Gate 3 — PoPP gap | |PoP − PoPP| ≤ 15% | HARD reject |

| Gate 4 — Min RoC | RoC ≥ 0.5% × (DTE/5) — DTE adjusted | HARD reject |



Gate 2 is indicative: use real expected loss (50% of max loss, as Agent 3 exits at T2)

not theoretical max loss for lot sizing.



\#### Layer 5 — Position Sizing

```

real\_expected\_loss\_per\_lot = max\_loss\_per\_lot × 0.50

max\_loss\_allowed = capital × 0.015

lots = floor(max\_loss\_allowed ÷ real\_expected\_loss\_per\_lot)

Also check: lots ≤ floor(capital ÷ SPAN\_margin\_per\_lot)

Final lots = min(lots\_from\_loss\_rule, lots\_from\_margin)

```



\#### Black-Scholes PoP Calculation

```

d1 = (ln(S/K) + (r + 0.5σ²)t) / (σ√t)

d2 = d1 - σ√t

PoP for short put = N(d2)   \[probability put expires OTM]

Delta of put = N(d1) - 1



S = Nifty spot, K = strike, r = 0.065 (repo rate),

σ = strike IV (from Upstox option chain), t = DTE/365

```



Implement Normal CDF using Apache Commons Math `NormalDistribution`.

All inputs/outputs BigDecimal. Convert to double only for Math functions, convert back.



\#### For Debit Spreads (Bull Call Spread)

```

PoP threshold: ≥ 35% (breakeven PoP, not max profit PoP)

PoPP: varies by profit target — show table of PoP at 0.5%, 1%, max profit

Exit recommendation: target Nifty level where 0.5% RoC is achieved

```



\### Design Pattern Guidance for Agent 2



Use \*\*Strategy Pattern\*\* for strategy selection:

```java

public interface StrategySelector {

&#x20;   StrategyType select(Agent1Signal signal, IvRegime ivRegime);

}

```



Use \*\*Factory Pattern\*\* for trade builders:

```java

public interface TradeBuilder {

&#x20;   TradeCard build(StrategyType type, MarketData data, UserProfile profile);

}

```



Use \*\*Chain of Responsibility\*\* for gate validation:

Each gate is a handler. If gate fails, chain stops. Result includes which gate failed.



\---



\## 9. AGENT 3 — MONITOR MODULE



Runs every 5 minutes during market hours 9:15 AM – 3:30 PM.

Reads monitor\_config from trades table. Checks live P\&L against thresholds.



\### Monitoring Thresholds



For SELL spreads (credit):

```

T1 WATCH    → Nifty falls to short\_strike + 150 pts

&#x20;             Action: Recalculate live PoP. Hold if PoP ≥ 75%



T2 READJUST → Nifty falls to short\_strike + 75 pts

&#x20;             OR mark-to-market loss = 50% of max\_loss\_theoretical

&#x20;             Action: Roll short strike lower, or tighten spread



T3 EXIT     → Nifty breaches short\_strike

&#x20;             OR mark-to-market loss = real\_expected\_loss (full T2 amount)

&#x20;             Action: Close ALL legs — market order

```



For BUY spreads (debit):

```

T1 PROFIT   → Nifty reaches 0.5% RoC target level → close all, book profit

T2 STRETCH  → Nifty reaches 1% RoC target → trail stop

T3 LOSS     → Mark-to-market loss = 50% of premium paid → close all

```



Dynamic overrides:

\- VIX spike > 30% intraday → override to T3 EXIT regardless of price

\- VIX > 24 (Extreme) → pause monitoring, alert user



\### REST Endpoint

```

POST /api/v1/agent3/evaluate/{trade\_id}

&#x20; Called by orchestrator scheduler every 5 min

&#x20; Response: { action: HOLD|WATCH|READJUST|EXIT, reason, current\_pnl }

```



\---



\## 10. AGENT 5 — EXECUTION MODULE



\### Purpose

Receives confirmed trade from Agent 2. Executes on Upstox v2 Order API.

Semi-auto in v1 — waits for user confirmation before placing.



\### Upstox API Hosts

\- Market data + margin check → `api.upstox.com` (production, `UPSTOX\_ACCESS\_TOKEN`)

\- Order placement / modify / cancel → `api-hft.upstox.com` (production) or `api-sandbox.upstox.com` (sandbox, `UPSTOX\_SANDBOX\_TOKEN`)

Both RestClient beans (`upstoxRestClient`, `upstoxOrderRestClient`) are auto-configured by core-module's `UpstoxAutoConfiguration`.



\### Order Identification

Every trade placed by this system carries two identifiers:

\- **tag** (shared across all legs of one trade): `ZUPP\_{tradeId\_first8\_uppercase}` — queryable from Upstox via `GET /v2/order/details?tag=...`

\- **correlation\_id** (per leg): `ZUPP\_{id8}\_L{legIndex}` — returned in multi/place response to map order\_id back to leg without state

Exit orders use tag `ZUPP\_{id8}\_X` and correlation\_id `ZUPP\_{id8}\_X\_L{legIndex}`.



\### Product Type

All Nifty weekly spread legs use product `"D"` (Delivery / NRML) — held overnight until Tuesday expiry.

Never use `"I"` (Intraday) — auto-squared at 3:20 PM.



\### Execution Sequence

1\. Receive `ExecuteTradeRequest` (tradeId + legs with instrumentKey, action, limitPrice, quantity)

2\. Read `expectedNetPremiumPerUnit` from trades table (status must be CONFIRMED)

3\. Margin check: `POST /v2/charges/margin` on `api.upstox.com` — reject if insufficient

4\. Place both legs simultaneously: `POST /v2/order/multi/place` on `api-hft.upstox.com`

   \- Payload validation is all-or-nothing (both legs accepted or both rejected)

   \- Exchange fills are NOT atomic — partial fills are possible

5\. Poll both order statuses simultaneously — 30s timeout

6\. On timeout: if `cancel-on-timeout-instead-of-market=true` (sandbox) → cancel; else modify to MARKET

7\. If one leg fills and the other rejects → place reverse MARKET order on filled leg (rollback), return FAILED

8\. Both filled → compute actual net premium from fill prices

9\. Slippage check: if actual net premium < expected × 0.90 → set slippageAlert=true (trade still live)

10\. Persist fills as JSON to `trades.entry\_fills`, update status to ACTIVE

11\. Pass confirmed fills to Agent 3 via monitor-config endpoint



\### Exit Sequence

1\. Receive `ExitTradeRequest` (tradeId, reason, legs with instrumentKey + originalAction + quantity)

2\. Place reverse MARKET multi/place (BUY→SELL, SELL→BUY) using exit tag

3\. Update `trades.status = CLOSED`, set `closed\_at` and `close\_reason`



\### Slippage threshold: 10% of expected net premium per unit



\### Key Upstox Order Endpoints

\- `POST /v2/order/multi/place` — simultaneous multi-leg entry

\- `GET /v2/order/details?order\_id={id}` — poll individual order status

\- `GET /v2/order/details?tag={tag}` — query all legs of a trade by tag

\- `PUT /v2/order/modify` — modify LIMIT → MARKET on timeout

\- `DELETE /v2/order/cancel?order\_id={id}` — cancel open order



\### Sandbox Testing

Run with `-Dspring.profiles.active=sandbox`:

\- Margin check hits real `api.upstox.com` (production token, no real orders)

\- Order placement hits `api-sandbox.upstox.com` (sandbox token, no real money)

\- Required env vars: `UPSTOX\_ACCESS\_TOKEN`, `UPSTOX\_SANDBOX\_TOKEN`, `DB\_USER`, `DB\_PASSWORD`

\- Run: `mvn test -pl agent5-execution -Dspring.profiles.active=sandbox -Dgroups=sandbox`



\---



\## 11. ORCHESTRATOR MODULE



\### Flow

1\. User authenticates (API key v1)

2\. Check DB for latest Agent 1 signal for current expiry

3\. If signal older than 15 minutes → call Agent 1 /score endpoint

4\. If VIX Extreme → do not proceed, alert user

5\. Call Agent 2 /recommend with fresh signal

6\. Send trade card to user for confirmation

7\. On CONFIRM → call Agent 2 /confirm → Agent 5 executes

8\. Start Agent 3 monitoring loop for the trade



\### Prefect-style flows (use Spring @Scheduled + Spring Events)

\- Morning setup flow: 9:00 AM and 9:20 AM

\- Monitor flow: every 5 min, 9:15 AM – 3:30 PM

\- Post-expiry report: Tuesday 4:00 PM



\---



\## 12. SHARED DOMAIN MODULE



\### Key Enums

```java

public enum Bias { BULLISH, BEARISH, NEUTRAL }

public enum Strength { EXTREME, MILD, WEAK }

public enum VixRegime { LOW, NORMAL, HIGH, EXTREME }

public enum IvRegime { RICH, FAIR, CHEAP }

public enum StrategyType { BULL\_PUT\_SPREAD, BEAR\_CALL\_SPREAD, BULL\_CALL\_SPREAD,

&#x20;                          BEAR\_PUT\_SPREAD, IRON\_CONDOR, SHORT\_STRADDLE,

&#x20;                          SHORT\_STRANGLE, SKIP }

public enum TradeStatus { PENDING\_CONFIRM, CONFIRMED, REJECTED, EXPIRED,

&#x20;                         ACTIVE, CLOSED }

public enum OptionType { CE, PE }

public enum LegAction { BUY, SELL }

public enum MonitorAction { HOLD, WATCH, READJUST, EXIT }

public enum ConfidenceLabel { LOW, MEDIUM, HIGH }

```



\### Key Constants

```java

public final class TradingConstants {

&#x20;   public static final String API\_KEY\_HEADER = "X-API-Key";

&#x20;   public static final BigDecimal RISK\_FREE\_RATE = new BigDecimal("0.065");

&#x20;   public static final int LOT\_SIZE\_KEY = "NIFTY\_LOT\_SIZE"; // fetch from DB

&#x20;   public static final BigDecimal VIX\_LOW\_THRESHOLD = new BigDecimal("13");

&#x20;   public static final BigDecimal VIX\_NORMAL\_HIGH = new BigDecimal("18");

&#x20;   public static final BigDecimal VIX\_HIGH\_EXTREME = new BigDecimal("24");

}

```



\---



\## 13. EXTERNAL API INTEGRATIONS



\### Upstox API

\- Auth: Access token (OAuth2). Store in DB/cache. Refresh when expired.

\- Option chain: `GET /v2/option/chain?instrument\_key=NSE\_INDEX|Nifty 50\&expiry\_date=YYYY-MM-DD`

&#x20; Returns per strike: LTP, OI, bid/ask, IV, Delta, Theta, Gamma, Vega, PoP, PCR

\- Historical OHLC: `GET /v2/historical-candle/{instrument\_key}/day/{to\_date}/{from\_date}`

\- Live WebSocket: MarketDataStreamerV3 for real-time spot and VIX

\- Rate limit: Handle 429 with exponential backoff



\### Marketaux API

\- News: `GET /v1/news/all?symbols=^NSEI\&api\_token={key}\&language=en\&limit=3`

\- Extract: `entities\[].sentiment\_score` where `symbol = "^NSEI"`

\- Average sentiment scores → apply threshold (>0.30 bullish, <-0.30 bearish)

\- Free tier: 100 requests/day — cache results aggressively



\### NSE Direct Downloads

\- SPAN margin file: Download daily at 9:00 AM. Direct file URL (stable).

\- Holiday calendar: Fetch monthly. Store in reference\_data table.

\*\*FII/DII data does NOT come from NSE.\*\* It comes from Upstox — see Upstox API section above.



\### Spring AI — Claude API

```java

// In agent1-direction module only

@Service

public class CommentaryExtractorService {

&#x20;   private final ChatClient chatClient;



&#x20;   public CommentaryExtractorService(ChatModel chatModel) {

&#x20;       this.chatClient = ChatClient.create(chatModel);

&#x20;   }



&#x20;   // Returns CommentarySignal with bias, conviction, nifty\_levels, bank\_nifty\_levels

&#x20;   public CommentarySignal extract(String commentary, BigDecimal marketauxSentiment) {

&#x20;       // System prompt: strict JSON only, no markdown, no preamble

&#x20;       // User prompt: commentary + marketaux avg + required JSON schema

&#x20;       // Parse with Jackson BeanOutputConverter or manually

&#x20;       // On parse failure: return CommentarySignal.neutral() — NEVER throw

&#x20;   }

}

```



System prompt for commentary extraction:

```

You are a financial market analyst assistant for an automated Nifty 50 options

trading system. Extract a structured market signal from the provided commentary

and news sentiment. Return ONLY valid JSON — no explanation, no markdown, no preamble.

Bias: Bullish, Bearish, or Neutral only.

All Nifty levels must be nearest 50. If range given, take midpoint.

If a level is not explicitly mentioned, return null — do not infer.

```



\---



\## 14. ERROR HANDLING STANDARDS



```java

// Custom exceptions in shared-domain

public class DataFetchException extends RuntimeException { ... }

public class InsufficientDataException extends RuntimeException { ... }

public class GateValidationException extends RuntimeException { ... }

public class TokenRefreshException extends RuntimeException { ... }



// Global exception handler in each module

@RestControllerAdvice

public class GlobalExceptionHandler {

&#x20;   // Map to RFC 9457 Problem Details

}

```



Never let external API failures propagate to trade decisions.

Every data fetch wrapped in try-catch. Missing data → score 0, log to data\_gaps.



\---



\## 15. CONFIGURATION (application.yml)



```yaml

nifty:

&#x20; trading:

&#x20;   # VIX thresholds

&#x20;   vix:

&#x20;     low-threshold: 13.0

&#x20;     normal-high: 18.0

&#x20;     high-extreme: 24.0

&#x20;   # IV/HV ratio thresholds

&#x20;   iv:

&#x20;     rich-threshold: 1.20

&#x20;     cheap-threshold: 0.85

&#x20;   # FII flow thresholds (crore)

&#x20;   fii:

&#x20;     significant-flow-crore: 500

&#x20;   # PCR thresholds

&#x20;   pcr:

&#x20;     bullish-above: 1.20

&#x20;     bearish-below: 0.80

&#x20;   # Gift Nifty threshold (points)

&#x20;   gift-nifty:

&#x20;     significant-pts: 50

&#x20;   # Scoring weights

&#x20;   scoring:

&#x20;     tier1a-weight: 0.30

&#x20;     tier1b-weight: 0.20

&#x20;     tier2-weight: 0.30

&#x20;     tier3-weight: 0.10

&#x20;     tier4-weight: 0.10

&#x20;   # Position sizing

&#x20;   position:

&#x20;     max-loss-pct: 1.5

&#x20;     real-loss-factor: 0.50

&#x20;     signal-staleness-minutes: 15

&#x20;   # Risk-free rate

&#x20;   risk-free-rate: 0.065



spring:

&#x20; ai:

&#x20;   anthropic:

&#x20;     api-key: ${ANTHROPIC\_API\_KEY}

&#x20;     chat:

&#x20;       options:

&#x20;         model: claude-sonnet-4-6

&#x20;         max-tokens: 500



upstox:

&#x20; api:

&#x20;   base-url: https://api.upstox.com          # market data + margin (not a secret)

&#x20;   order-base-url: https://api-hft.upstox.com # HFT order host (not a secret)

&#x20;   access-token: ${UPSTOX\_ACCESS\_TOKEN}       # production token — env var

&#x20;   # order-access-token: leave unset in production (falls back to access-token)

&#x20;   # order-access-token: ${UPSTOX\_SANDBOX\_TOKEN} ← set only in application-sandbox.yml



agent5:

&#x20; execution:

&#x20;   fill-poll-interval-ms: 5000

&#x20;   fill-timeout-ms: 30000

&#x20;   slippage-alert-threshold: 0.10

&#x20;   product: D                                 # NRML — never I (intraday)

&#x20;   cancel-on-timeout-instead-of-market: false # true in sandbox profile



marketaux:

&#x20; api:

&#x20;   key: ${MARKETAUX\_API\_KEY}

&#x20;   base-url: https://api.marketaux.com

```



\---



\## 16. DOCKER COMPOSE



```yaml

version: '3.8'

services:

&#x20; postgres:

&#x20;   image: postgres:16

&#x20;   environment:

&#x20;     POSTGRES\_DB: nifty\_trading

&#x20;     POSTGRES\_USER: ${DB\_USER}

&#x20;     POSTGRES\_PASSWORD: ${DB\_PASSWORD}

&#x20;   ports: \["5432:5432"]

&#x20;   volumes: \["postgres\_data:/var/lib/postgresql/data"]



&#x20; agent1-direction:

&#x20;   build: ./agent1-direction

&#x20;   depends\_on: \[postgres]

&#x20;   environment:

&#x20;     SPRING\_DATASOURCE\_URL: jdbc:postgresql://postgres:5432/nifty\_trading

&#x20;     ANTHROPIC\_API\_KEY: ${ANTHROPIC\_API\_KEY}

&#x20;     UPSTOX\_ACCESS\_TOKEN: ${UPSTOX\_ACCESS\_TOKEN}

&#x20;     MARKETAUX\_API\_KEY: ${MARKETAUX\_API\_KEY}

&#x20;     X\_API\_KEY: ${INTERNAL\_API\_KEY}

&#x20;   ports: \["8081:8080"]



&#x20; agent2-recommendation:

&#x20;   build: ./agent2-recommendation

&#x20;   depends\_on: \[postgres, agent1-direction]

&#x20;   environment:

&#x20;     SPRING\_DATASOURCE\_URL: jdbc:postgresql://postgres:5432/nifty\_trading

&#x20;     UPSTOX\_ACCESS\_TOKEN: ${UPSTOX\_ACCESS\_TOKEN}

&#x20;     X\_API\_KEY: ${INTERNAL\_API\_KEY}

&#x20;   ports: \["8082:8080"]



&#x20; # agent3, agent4, agent5, orchestrator follow same pattern



volumes:

&#x20; postgres\_data:

```



\---



\## 17. BUILD ORDER



Build and implement in this sequence:



1\. \*\*shared-domain\*\* — enums, DTOs, constants, exceptions. No Spring Boot.

2\. \*\*Flyway migrations\*\* — V1\_\_init.sql with all tables above.

3\. \*\*agent1-direction\*\* — scoring model first (pure unit-testable), then API clients, then REST endpoints.

4\. \*\*agent2-recommendation\*\* — Black-Scholes first (unit test), then strategy selector, then REST endpoints.

5\. \*\*agent5-execution\*\* — Upstox v2 Order API integration (multi/place, margin check, status poll, rollback).

6\. \*\*agent3-monitor\*\* — monitoring loop.

7\. \*\*orchestrator\*\* — wire everything together.

8\. \*\*agent4-backtest\*\* — last, after core system is stable.



\---



\## 18. TESTING REQUIREMENTS



Every module must have:

\- Unit tests for all scoring calculations (Agent 1 tiers)

\- Unit tests for all gate validations (Agent 2)

\- Unit tests for Black-Scholes PoP calculation

\- Integration tests for REST endpoints (MockMvc)

\- Mocked external API tests (MockRestServiceServer for Upstox/Marketaux)



Test the backtest scenario from session (15th May 2026):

```

Inputs: spot=23412.60, 20EMA=23900, 50EMA=23690, PCR=1.17,

&#x20;       FII long ratio=0.11, DII net=684Cr, VIX=18.61,

&#x20;       VIX prev=19.43, Gift Nifty +70 pts,

&#x20;       Marketaux sentiment=-0.335

Expected output: Bias=NEUTRAL, Strength=WEAK, Score≈0.067, Confidence=LOW

```



\---



\## 19. WHAT NOT TO DO



\- Do not use float or double for any financial calculation

\- Do not hardcode lot size (65), VIX levels, or expiry dates

\- Do not call external APIs inside JPA transactions

\- Do not use @Autowired field injection — use constructor injection

\- Do not swallow exceptions silently — log with full context

\- Do not use Optional.get() without isPresent() check

\- Do not place orders without confirming both legs filled

\- Do not let LLM failures block the scoring pipeline

\- Do not store API keys in code or application.yml — use env vars



\---



\## 20. FIRST TASK FOR CLAUDE CODE



\*\*Start with `shared-domain` module.\*\*



Before writing any code, confirm:

1\. Package structure: `com.nifty.shared` — agreed?

2\. Error handling: RFC 9457 Problem Details — agreed?

3\. All enums listed in Section 12 — any to add or change?

4\. TA4J version to use — check Maven Central for latest stable.

5\. Spring AI Anthropic starter version — check for latest stable milestone.



Then implement in order:

1\. All enums

2\. TradingConstants

3\. Core DTOs (Agent1Signal, TradeCard, CommentarySignal, MarketInputs)

4\. Custom exceptions

5\. Utility class for BigDecimal rounding standards used throughout



Do not start `agent1-direction` until `shared-domain` compiles cleanly.

