package com.the3Cgrp.zupptrade.agent4.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

    /** All signal rows in range — Java groups and aggregates. */
    public List<Map<String, Object>> findSignals(LocalDate from, LocalDate to) {
        String sql = "SELECT signal_id, scored_at, bias, strength, composite_score, "
                + "confidence_label, vix_regime, commentary_divergence, "
                + "data_gaps_json, trade_id, strategy, trade_outcome, accuracy_verdict "
                + "FROM " + VIEW
                + " WHERE (CAST(? AS DATE) IS NULL OR scored_at >= CAST(? AS DATE)) "
                + "   AND (CAST(? AS DATE) IS NULL OR scored_at <= CAST(? AS DATE) + INTERVAL '1 day') "
                + " ORDER BY scored_at DESC";
        Object fromVal = from != null ? java.sql.Date.valueOf(from) : null;
        Object toVal   = to   != null ? java.sql.Date.valueOf(to)   : null;
        return jdbc.queryForList(sql, fromVal, fromVal, toVal, toVal);
    }
}
