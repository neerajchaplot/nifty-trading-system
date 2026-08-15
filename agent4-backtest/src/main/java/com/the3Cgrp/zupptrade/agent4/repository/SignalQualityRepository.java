package com.the3Cgrp.zupptrade.agent4.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads v_agent4_signal_quality for the signal quality report API.
 * accuracy_verdict and trade outcome are already pre-computed by the view.
 */
@Repository
public class SignalQualityRepository {

    private static final String VIEW = "zupptrade_dev.v_agent4_signal_quality";

    private final JdbcTemplate jdbc;

    public SignalQualityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * All signal rows in range — Java groups, computes the price-based accuracy verdict,
     * and aggregates. Exposes signal_spot / expiry_date / expiry_close so
     * {@code Agent1AccuracyCalculator} can grade each signal; there is no accuracy_verdict
     * column (verdict moved out of SQL into config-driven Java).
     */
    public List<Map<String, Object>> findSignals(LocalDate from, LocalDate to, UUID scope) {
        String sql = "SELECT signal_id, scored_at, bias, strength, composite_score, "
                + "confidence_label, vix_regime, commentary_divergence, data_gaps_json, "
                + "signal_spot, expiry_date, expiry_close, "
                + "trade_id, strategy, trade_outcome "
                + "FROM " + VIEW
                + " WHERE (CAST(? AS DATE) IS NULL OR scored_at >= CAST(? AS DATE)) "
                + "   AND (CAST(? AS DATE) IS NULL OR scored_at <= CAST(? AS DATE) + INTERVAL '1 day') "
                // Per-user scope (Phase 5): NULL scope = admin (all signals); else this owner only.
                + "   AND (CAST(? AS UUID) IS NULL OR user_profile_id = CAST(? AS UUID)) "
                + " ORDER BY scored_at DESC";
        Object fromVal  = from  != null ? java.sql.Date.valueOf(from) : null;
        Object toVal    = to    != null ? java.sql.Date.valueOf(to)   : null;
        Object scopeVal = scope != null ? scope.toString()           : null;
        return jdbc.queryForList(sql, fromVal, fromVal, toVal, toVal, scopeVal, scopeVal);
    }
}
