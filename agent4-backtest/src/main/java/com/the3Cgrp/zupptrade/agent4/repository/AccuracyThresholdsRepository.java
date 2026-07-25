package com.the3Cgrp.zupptrade.agent4.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.the3Cgrp.zupptrade.agent4.calculator.Agent1AccuracyCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * Reads the Agent 1 accuracy thresholds from reference_data (key AGENT1_ACCURACY_THRESHOLDS),
 * so the point thresholds live in the DB and can be tuned without a redeploy (rule #2 — no
 * hardcoded values). A documented default is used ONLY as a logged fallback when the row is
 * missing or unparseable, so the report never silently guesses.
 */
@Repository
public class AccuracyThresholdsRepository {

    private static final Logger log = LoggerFactory.getLogger(AccuracyThresholdsRepository.class);
    private static final String KEY = "AGENT1_ACCURACY_THRESHOLDS";

    /** Fallback only — the authoritative source is the reference_data row seeded in V110. */
    private static final Agent1AccuracyCalculator.Thresholds DEFAULT =
            new Agent1AccuracyCalculator.Thresholds(
                    new BigDecimal("200"), new BigDecimal("100"), new BigDecimal("100"));

    private final JdbcTemplate jdbc;
    // Jackson 2 ObjectMapper — not a Spring Boot 4 bean (it uses Jackson 3), so created directly.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AccuracyThresholdsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Agent1AccuracyCalculator.Thresholds get() {
        try {
            String json = jdbc.queryForObject(
                    "SELECT value::text FROM zupptrade_dev.reference_data WHERE key = ?",
                    String.class, KEY);
            if (json == null || json.isBlank()) {
                log.warn("accuracy_thresholds.empty key={} — using defaults {}", KEY, DEFAULT);
                return DEFAULT;
            }
            JsonNode node = objectMapper.readTree(json);
            return new Agent1AccuracyCalculator.Thresholds(
                    field(node, "extremePoints",     DEFAULT.extremePoints()),
                    field(node, "mildPoints",        DEFAULT.mildPoints()),
                    field(node, "neutralBandPoints", DEFAULT.neutralBandPoints()));
        } catch (EmptyResultDataAccessException e) {
            log.warn("accuracy_thresholds.missing key={} — using defaults {}", KEY, DEFAULT);
            return DEFAULT;
        } catch (Exception e) {
            log.warn("accuracy_thresholds.parse_failed key={} error={} — using defaults {}",
                    KEY, e.getMessage(), DEFAULT);
            return DEFAULT;
        }
    }

    private BigDecimal field(JsonNode node, String name, BigDecimal fallback) {
        JsonNode v = node.get(name);
        if (v == null || v.isNull()) return fallback;
        try {
            return new BigDecimal(v.asText());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
