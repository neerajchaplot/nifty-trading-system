package com.the3Cgrp.zupptrade.core.expiry;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Caching layer for Nifty expiry dates.
 *
 * Source: Upstox /v2/option/contract (via UpstoxExpiryClient).
 * Cache: reference_data table, key = NIFTY_EXPIRY_DATES, TTL = 7 days.
 *
 * nextExpiry() returns the nearest upcoming date >= today.
 * Callers should always handle the null case (e.g., when Upstox is down and cache is empty).
 */
public class ExpiryDateService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryDateService.class);
    private static final String CACHE_KEY = "NIFTY_EXPIRY_DATES";
    private static final int CACHE_TTL_HOURS = 168; // 7 days — calendar rarely changes

    private static final TypeReference<List<LocalDate>> DATE_LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final UpstoxExpiryClient expiryClient;
    private final ObjectMapper mapper;

    public ExpiryDateService(JdbcTemplate jdbc, UpstoxExpiryClient expiryClient, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.expiryClient = expiryClient;
        this.mapper = mapper;
    }

    /**
     * Returns the closest upcoming Nifty expiry date (>= today).
     * Returns null if no upcoming date is found (empty cache + Upstox unavailable).
     */
    public LocalDate nextExpiry() {
        List<LocalDate> all = allExpiries();
        LocalDate today = LocalDate.now();
        return all.stream()
                .filter(d -> !d.isBefore(today))
                .findFirst()
                .orElse(null);
    }

    /**
     * All Nifty expiry dates (past + future), sorted ascending.
     * Hits DB cache first; refreshes from Upstox when stale.
     */
    public List<LocalDate> allExpiries() {
        List<LocalDate> cached = loadFromCache();
        if (cached != null) return cached;
        return refresh();
    }

    /**
     * Forces a refresh from Upstox and updates the cache.
     * Call this if you suspect the cache is stale (e.g., after a series rollover).
     */
    public List<LocalDate> refresh() {
        List<LocalDate> fresh = expiryClient.fetchAllExpiries();
        if (!fresh.isEmpty()) {
            saveToCache(fresh);
        } else {
            log.warn("expiry.cache.refresh.empty — Upstox returned no expiry dates; keeping stale cache");
        }
        return fresh;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<LocalDate> loadFromCache() {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    """
                    SELECT value, fetched_at, ttl_hours
                    FROM reference_data
                    WHERE key = ?
                      AND fetched_at + (ttl_hours * interval '1 hour') > NOW()
                    """,
                    CACHE_KEY);

            String json = (String) row.get("value");
            List<LocalDate> dates = mapper.readValue(json, DATE_LIST_TYPE);
            log.debug("expiry.cache.hit count={}", dates.size());
            return dates;

        } catch (EmptyResultDataAccessException ex) {
            log.debug("expiry.cache.miss key={}", CACHE_KEY);
            return null;
        } catch (Exception ex) {
            log.warn("expiry.cache.read.error — will refresh from Upstox", ex);
            return null;
        }
    }

    private void saveToCache(List<LocalDate> dates) {
        try {
            String json = mapper.writeValueAsString(dates);
            jdbc.update(
                    """
                    INSERT INTO reference_data (key, value, source, fetched_at, ttl_hours)
                    VALUES (?, ?::jsonb, 'upstox-option-contract', NOW(), ?)
                    ON CONFLICT (key) DO UPDATE
                      SET value = EXCLUDED.value,
                          source = EXCLUDED.source,
                          fetched_at = NOW(),
                          ttl_hours = EXCLUDED.ttl_hours
                    """,
                    CACHE_KEY, json, CACHE_TTL_HOURS);
            log.info("expiry.cache.saved count={}", dates.size());
        } catch (Exception ex) {
            log.error("expiry.cache.save.error", ex);
        }
    }
}
