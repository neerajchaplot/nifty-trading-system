-- ============================================================
-- V111 — Rebuild v_agent4_signal_quality for price-based accuracy
--
-- The old view computed accuracy_verdict in SQL as a P&L proxy:
-- "did the directional trade derived from this signal make money."
-- That was trade-coupled (empty when no closed directional trade) and
-- measured trade P&L, not whether the market did what the signal promised.
--
-- New model: signal accuracy is a property of the SIGNAL alone — did Nifty
-- move from the signal's scoring-time spot to its expiry-day close the way the
-- signal's bias+strength promised. The verdict is now computed in Agent 4 Java
-- (Agent1AccuracyCalculator) using DB-driven thresholds, so this view only
-- EXPOSES THE RAW INPUTS: signal_spot and expiry_close. The accuracy_verdict
-- column is removed.
--
-- CREATE OR REPLACE cannot drop an existing column, so we DROP + CREATE.
-- Only Agent 4 reads this view.
-- ============================================================

DROP VIEW IF EXISTS zupptrade_dev.v_agent4_signal_quality;

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

COMMENT ON VIEW zupptrade_dev.v_agent4_signal_quality IS
    'Agent 4 signal quality: one row per agent1_signals record. Exposes signal_spot and '
    'expiry_close (joined from nifty_daily_close on expiry_date) so Agent 4 can compute a '
    'price-based accuracy verdict in Java. Trade linkage retained only for trade/skip counts.';
