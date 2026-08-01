-- ============================================================
-- V114 — Widen trades.close_reason from VARCHAR(100) to TEXT.
--
-- Agent 5 writes diagnostic rejection/close reasons here (which leg failed, order id, whether
-- filled legs were rolled back). These messages routinely exceed 100 chars, so the UPDATE failed
-- with SQLSTATE 22001 (string data right truncation) and was swallowed — leaving a rejected trade
-- stuck in its prior status while the API reported REJECTED. TEXT removes the cap.
--
-- Postgres refuses ALTER COLUMN TYPE while a view depends on the column, so we DROP the two Agent 4
-- views that select trades.close_reason, alter the column, then recreate them VERBATIM from their
-- current definitions (v_agent4_trade_list → V104, v_agent4_signal_quality → V111). No behavioural
-- change to either view — only the underlying column type widens.
-- ============================================================

DROP VIEW IF EXISTS zupptrade_dev.v_agent4_trade_list;
DROP VIEW IF EXISTS zupptrade_dev.v_agent4_signal_quality;

ALTER TABLE zupptrade_dev.trades ALTER COLUMN close_reason TYPE TEXT;

-- ── Recreate View 1: v_agent4_trade_list (unchanged from V104) ───────────────
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
    me_agg.exit_triggered_by

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

-- ── Recreate View 2: v_agent4_signal_quality (unchanged from V111) ───────────
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
    END                                                                     AS trade_outcome

FROM zupptrade_dev.agent1_signals s
LEFT JOIN zupptrade_dev.nifty_daily_close nc
    ON nc.trade_date = s.expiry_date
LEFT JOIN zupptrade_dev.trades t
    ON  t.agent1_signal_id = s.id
    AND t.status = 'CLOSED';

-- ── Restore view comments ────────────────────────────────────────────────────
COMMENT ON VIEW zupptrade_dev.v_agent4_trade_list IS
    'Agent 4 analytics: one row per trade (all statuses). '
    'Java adds WHERE status=''CLOSED'', optional date range, ORDER BY closed_at DESC, LIMIT/OFFSET.';

COMMENT ON VIEW zupptrade_dev.v_agent4_signal_quality IS
    'Agent 4 signal quality: one row per agent1_signals record. Exposes signal_spot and '
    'expiry_close (joined from nifty_daily_close on expiry_date) so Agent 4 can compute a '
    'price-based accuracy verdict in Java. Trade linkage retained only for trade/skip counts.';
