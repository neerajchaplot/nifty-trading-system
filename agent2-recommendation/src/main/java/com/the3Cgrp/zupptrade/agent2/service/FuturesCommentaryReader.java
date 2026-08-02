package com.the3Cgrp.zupptrade.agent2.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads the admin-submitted mandatory daily commentary for the futures module
 * (table {@code futures_daily_commentary}, one row per trade_date, inserted out-of-band by an admin).
 */
@Service
public class FuturesCommentaryReader {

    private final JdbcTemplate jdbc;

    public FuturesCommentaryReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Admin commentary for the given trade date, if present and non-blank. */
    public Optional<String> findCommentary(LocalDate tradeDate) {
        List<String> rows = jdbc.query(
                "SELECT commentary FROM futures_daily_commentary WHERE trade_date = ?",
                (rs, n) -> rs.getString("commentary"), tradeDate);
        return rows.stream().findFirst().filter(c -> c != null && !c.isBlank());
    }
}
