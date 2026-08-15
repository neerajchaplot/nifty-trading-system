-- ============================================================
-- V126 — Multi-user Phase 5: expose user_profile_id for read scoping
--
-- The agent4 analytics views project trade/signal columns but DROP the owning
-- user_profile_id, so agent4 cannot filter a user's own rows. This migration
-- re-creates both views with user_profile_id appended, and adds the indexes the
-- new WHERE clauses will use.
--
-- Reproduced VERBATIM from the CURRENT definitions and then extended:
--   v_agent4_trade_list     → V104 (unchanged since)          + t.user_profile_id
--   v_agent4_signal_quality → V111/V114 (signal_spot, etc.)   + s.user_profile_id
-- Uses DROP + CREATE (the same pattern V114 used) so the new column can be added
-- without the CREATE-OR-REPLACE append-only restriction. Neither DROP needs
-- CASCADE — nothing depends on these views.
--
-- Scoping keys:
--   v_agent4_trade_list     → t.user_profile_id (trade owner)
--   v_agent4_signal_quality → s.user_profile_id (signal owner; column added in V120)
-- ============================================================

DROP VIEW IF EXISTS zupptrade_dev.v_agent4_trade_list;
DROP VIEW IF EXISTS zupptrade_dev.v_agent4_signal_quality;

-- ── View 1: v_agent4_trade_list (V104 definition + user_profile_id) ───────────
CREATE VIEW zupptrade_dev.v_agent4_trade_list AS
SELECT
    -- Trade identity
    t.id                                                                    AS trade_id,
    t.trade_code,
    t.strategy,
    t.spread_direction,
    t.expiry_date,
    t.status,
    t.dte,

    -- Lifecycle timestamps
    t.confirmed_at                                                          AS entry_date,
    t.closed_at                                                             AS exit_date,
    CASE
        WHEN t.confirmed_at IS NOT NULL AND t.closed_at IS NOT NULL
            THEN EXTRACT(DAY FROM (t.closed_at - t.confirmed_at))::INT
        ELSE NULL
    END                                                                     AS holding_days,

    -- Outcome label
    CASE
        WHEN t.actual_pnl > 0  THEN 'WIN'
        WHEN t.actual_pnl <= 0 THEN 'LOSS'
        ELSE 'OPEN'
    END                                                                     AS outcome,
    t.actual_pnl,
    t.close_reason,

    -- Actual RoC: actual_pnl / realExpectedLossTotal × 100
    -- NULLIF guards zero-denominator (should not occur on valid trades)
    CASE
        WHEN (t.summary->>'realExpectedLossTotal')::DECIMAL <> 0
            THEN ROUND(
                t.actual_pnl / (t.summary->>'realExpectedLossTotal')::DECIMAL * 100,
                4)
        ELSE NULL
    END                                                                     AS roc_achieved_pct,

    -- Summary JSONB extractions (field names from TradeSummary record, Jackson camelCase)
    (t.summary->>'lots')::INT                                               AS lots,
    (t.summary->>'lotSize')::INT                                            AS lot_size,
    (t.summary->>'netPremiumPerUnit')::DECIMAL                              AS entry_net_premium,
    (t.summary->>'maxProfitTotal')::DECIMAL                                 AS max_profit_total,
    (t.summary->>'theoreticalMaxLossTotal')::DECIMAL                        AS theoretical_max_loss_total,
    (t.summary->>'realExpectedLossTotal')::DECIMAL                          AS real_expected_loss_total,
    (t.summary->>'pop')::DECIMAL                                            AS pop,
    (t.summary->>'popp')::DECIMAL                                           AS popp,
    (t.summary->>'roc')::DECIMAL                                            AS roc_theoretical_pct,
    (t.summary->>'rocAnnualised')::DECIMAL                                  AS roc_annualised,

    -- Market context at entry (field names from MarketContext record)
    (t.market_context->>'spot')::DECIMAL                                    AS entry_spot,
    (t.market_context->>'vix')::DECIMAL                                     AS entry_vix,
    t.market_context->>'vixRegime'                                          AS entry_vix_regime,
    t.market_context->>'ivRegime'                                           AS entry_iv_regime,

    -- Thresholds JSONB extractions (field names from MonitorThresholdsDto record)
    (t.thresholds->>'t1WatchNiftyLevel')::DECIMAL                           AS t1_watch_nifty,
    (t.thresholds->>'t2ReadjustNiftyLevel')::DECIMAL                        AS t2_readjust_nifty,
    (t.thresholds->>'t2LossThreshold')::DECIMAL                             AS t2_loss_threshold,
    (t.thresholds->>'t3ExitNiftyLevel')::DECIMAL                            AS t3_exit_nifty,
    (t.thresholds->>'t3LossThreshold')::DECIMAL                             AS t3_loss_threshold,

    -- Gate results: stored as JSON array of GateResultDto — passed through to mapper
    -- Array format: [{"gate":"G1","passed":true,"description":"...","value":...,"threshold":...}, ...]
    t.gate_results::TEXT                                                    AS gate_results_json,

    -- Legs: passed through as-is for audit mapper to deserialise
    t.legs::TEXT                                                            AS legs_json,

    -- Agent 1 signal fields
    s.id                                                                    AS signal_id,
    s.timestamp                                                             AS signal_scored_at,
    s.bias                                                                  AS signal_bias,
    s.strength                                                              AS signal_strength,
    s.composite_score                                                       AS signal_score,
    s.confidence                                                            AS signal_confidence_score,
    s.confidence_label                                                      AS signal_confidence_label,
    s.vix_level                                                             AS signal_vix_level,
    s.vix_regime                                                            AS signal_vix_regime,
    s.vix_direction                                                         AS signal_vix_direction,
    s.commentary_divergence,
    s.score_breakdown::TEXT                                                 AS signal_score_breakdown,
    s.data_gaps::TEXT                                                       AS signal_data_gaps,

    -- Monitoring aggregates (subquery so the main SELECT stays readable)
    COALESCE(me_agg.adjustment_count, 0)                                    AS adjustment_count,
    COALESCE(me_agg.watch_count, 0)                                         AS watch_count,
    COALESCE(me_agg.hold_count, 0)                                          AS hold_count,
    me_agg.last_action,
    me_agg.exit_triggered_by,

    -- Phase 5 read scoping: the owning user (NULL for legacy/unattributed rows)
    t.user_profile_id                                                       AS user_profile_id

FROM zupptrade_dev.trades t
LEFT JOIN zupptrade_dev.agent1_signals s
    ON s.id = t.agent1_signal_id
LEFT JOIN (
    SELECT
        trade_id,
        COUNT(*) FILTER (WHERE action = 'READJUST')                               AS adjustment_count,
        COUNT(*) FILTER (WHERE action = 'WATCH')                                  AS watch_count,
        COUNT(*) FILTER (WHERE action = 'HOLD')                                   AS hold_count,
        (ARRAY_AGG(action      ORDER BY evaluated_at DESC))[1]                    AS last_action,
        (ARRAY_AGG(threshold_hit ORDER BY evaluated_at DESC)
            FILTER (WHERE action = 'EXIT'))[1]                                    AS exit_triggered_by
    FROM zupptrade_dev.monitoring_evaluations
    GROUP BY trade_id
) me_agg ON me_agg.trade_id = t.id;

-- ── View 2: v_agent4_signal_quality (V111/V114 definition + user_profile_id) ──
CREATE VIEW zupptrade_dev.v_agent4_signal_quality AS
SELECT
    s.id                                                                    AS signal_id,
    s.timestamp                                                             AS scored_at,
    s.expiry_date,
    s.bias,
    s.strength,
    s.composite_score,
    s.confidence_label,
    s.confidence                                                            AS confidence_score,
    s.vix_level,
    s.vix_regime,
    s.commentary_divergence,
    s.data_gaps::TEXT                                                       AS data_gaps_json,
    s.score_breakdown::TEXT                                                 AS score_breakdown_json,

    -- Price-accuracy inputs (verdict itself is computed in Agent 4 Java)
    s.spot                                                                  AS signal_spot,
    nc.close                                                                AS expiry_close,

    -- Linked closed trade (null when signal led to SKIP or trade not yet closed).
    -- Retained ONLY for the "signals leading to trade" / skip counts — NOT for accuracy.
    t.id                                                                    AS trade_id,
    t.strategy,
    t.spread_direction,
    t.status                                                                AS trade_status,
    t.actual_pnl,
    t.close_reason,

    CASE
        WHEN t.id IS NULL           THEN 'NO_TRADE'
        WHEN t.actual_pnl > 0       THEN 'WIN'
        WHEN t.actual_pnl <= 0      THEN 'LOSS'
        ELSE 'OPEN'
    END                                                                     AS trade_outcome,

    -- Phase 5 read scoping: the owning user of the signal (column added in V120)
    s.user_profile_id                                                       AS user_profile_id

FROM zupptrade_dev.agent1_signals s
LEFT JOIN zupptrade_dev.nifty_daily_close nc
    ON nc.trade_date = s.expiry_date
LEFT JOIN zupptrade_dev.trades t
    ON  t.agent1_signal_id = s.id
    AND t.status = 'CLOSED';

-- ── Restore view comments ────────────────────────────────────────────────────
COMMENT ON VIEW zupptrade_dev.v_agent4_trade_list IS
    'Agent 4 analytics: one row per trade (all statuses). '
    'Java adds WHERE status=''CLOSED'', optional date range, per-user scope on user_profile_id, '
    'ORDER BY closed_at DESC, LIMIT/OFFSET.';

COMMENT ON VIEW zupptrade_dev.v_agent4_signal_quality IS
    'Agent 4 signal quality: one row per agent1_signals record. Exposes signal_spot and '
    'expiry_close (joined from nifty_daily_close on expiry_date) so Agent 4 can compute a '
    'price-based accuracy verdict in Java. Trade linkage retained only for trade/skip counts. '
    'Per-user scope on user_profile_id.';


-- ── Scoping indexes ──────────────────────────────────────────
-- Composite (user, status) serves both the CLOSED analytics scans and the
-- ACTIVE/EXIT_FAILED monitor list once they filter by owner.
CREATE INDEX IF NOT EXISTS idx_trades_user_status
    ON zupptrade_dev.trades (user_profile_id, status);

-- Serves agent1 /latest scoped by owner + expiry.
CREATE INDEX IF NOT EXISTS idx_agent1_signals_user_expiry
    ON zupptrade_dev.agent1_signals (user_profile_id, expiry_date);
