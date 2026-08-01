# Simulator — status & remaining TODO

**Vision:** one folder per scenario holding all the data. Start the agents with
`--simulation.scenario=<name>` (simulation profile), and the whole pipeline runs end-to-end
from the folder — Agent 1 → Agent 2 → Agent 5 entry, then Agent 3's 5-day monitoring compressed
via a virtual clock — with no Upstox. Copy the folder, tweak a CSV, rerun.

## ✅ Done (built + compiles clean)

- **Scenario folder** format + example `scenarios/bull_put_5day/`
  (`scenario.yaml`, `spot_vix.csv`, `option_chain.csv`, `candles.csv`, `fii_dii.csv`, `sentiment.txt`, `commentary.txt`)
- **`gen_chain.py`** — generates the full option chain from the spot path
- **Shared engine** (shared-domain): `ScenarioReader`, `ScenarioMeta`, `SimClock`/`SimClockStore`
- **Agent 5** — offline execution (simulate-fills/exit), fault modes (`X-Sim-Fill-Mode`), `trade_pnl` on close
- **Agent 3** — reads market from the folder at the virtual clock, autonomous 5-min monitoring,
  EXIT → Agent 5, READJUST stub, `/sim/clock` + `/sim/run-cycle`
- **Agent 2** — reads spot/VIX + option chain from the folder, real recommendation, DTE from the scenario date
- **`run_scenario.sh`** — walks the timeline (drives Agent 3 monitoring)

## ⬜ Remaining (the path to the full vision)

1. ~~**Agent 1** — read its inputs from the folder and run the real 5-tier scoring.~~ ✅ DONE
   (`MarketInputsProvider` seam + `SimulatedMarketInputsProvider` + fixed-clock `SimulationConfig`;
   compiles clean.)
2. **Conductor: add the ENTRY step** — trigger Agent 1 score → Agent 2 recommend → confirm →
   Agent 5 execute at `entry.at`, THEN hand off to the monitoring walk. (Today the conductor only
   does monitoring; entry is still seeded.)
3. **Data**: expand `candles.csv` to ~200 rows (needed for the 200-day EMA).
4. **Run it all together** in simulation mode with the scenario and confirm the full end-to-end
   flow, fixing whatever surfaces. *(This needs your machine — I can't run the 4 services here.)*

## Definition of "done"

Start the agents with `--simulation.scenario=bull_put_5day`, run one conductor command, and it does:
**load scenario → Agent 1 signal → Agent 2 trade → execute → 5-day monitor → exit** — all from the
folder, no Upstox. Then you copy the folder, tweak, and rerun for a new scenario.

## Optional polish (later, not blocking the vision)

- `docker-compose.sim.yml` — one-command startup for all agents
- Gift-Nifty + LLM-commentary inputs (minor scoring tiers, skipped in sim for now)
- Hard prod-safety assertion (the `simulation` profile can never run in production)
- Capture real golden fixtures; expiry `trade_pnl` positions-sync

## Note on earlier detours

Two things got built as stepping-stones before we settled on the folder-driven design and are NOT
needed for it: the manual `/evaluate?act=true` seam and the golden-fixture contract test. They're
harmless and compile; ignore them for the end-to-end vision.
