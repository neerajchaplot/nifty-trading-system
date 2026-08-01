# Scenarios — the data you author

One folder per scenario. Copy a folder, tweak the files, rename it, and pass its name at startup:

```
--simulation.scenario=<folder-name>
```

Every agent reads **this folder** instead of Upstox/Marketaux, serving each data point **as of the
current virtual clock time**. Timelines are *step functions*: the most recent row at-or-before the
virtual time (for `spot_vix`) or date (for the rest) wins.

## Files

| File | Columns | Used by | Required |
|---|---|---|---|
| `scenario.yaml` | `name`, `clock{start,end,step}`, `entry{at,userProfile,expiry}` | conductor | ✅ |
| `spot_vix.csv` | `time` (ISO offset-datetime), `spot`, `vix` | Agents 1/2/3 | ✅ |
| `option_chain.csv` | `date`, `strike`, `type` (CE\|PE), `ltp`, `iv`, `delta`, `oi`, `pop` | Agents 1/2/3/5 | ✅ |
| `candles.csv` | `date`, `open`, `high`, `low`, `close` | Agent 1 (EMA/RSI/MACD) | for entry |
| `fii_dii.csv` | `date`, `fii_fut_net`, `fii_opt_net`, `dii_net`, `fii_long_ratio` (₹Cr) | Agent 1 (tier 2) | for entry |
| `commentary.txt` | free text | Agent 1 (tier 4) | optional |
| `sentiment.txt` | a single number in [-1, 1] | Agent 1 (tier 4) | optional |

- Lines starting with `#` are comments; the first non-comment line is the header.
- A **200-EMA** needs ~200 `candles.csv` rows; the sample folder ships a short set — add more as needed.
- Missing optional files just yield empty results (the agent logs a data gap, exactly like production).

## Sample

`bull_put_5day/` — a bull-put spread (SELL 24000 PE / BUY 23900 PE) entered Wed; spot drifts down
across 5 sessions and breaches the short strike near Tuesday expiry → WATCH → EXIT.

## Reader

The parsing/"as-of" engine is `com.the3Cgrp.zupptrade.shared.simulation.ScenarioReader` in
`shared-domain` (plain Java, reused by every agent). Each agent wraps it in `@Profile("simulation")`
beans that stand in for its Upstox/Marketaux clients.
