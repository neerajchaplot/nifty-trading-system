package com.the3Cgrp.zupptrade.core.expiry;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    // NSE options settle at 15:30 IST on expiry day. Before that, expiry day is still the live
    // contract; after that, it has settled and the "next" expiry must roll to the following week.
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime EXPIRY_CUTOFF = LocalTime.of(15, 30);

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
     * Returns the closest still-open Nifty expiry date, in IST.
     * <p>
     * Expiry day counts as "current" only until 15:30 IST (settlement). After the cutoff — e.g. an
     * evening re-score on expiry Tuesday — today's expiry has already settled, so this rolls forward
     * to the next weekly expiry. Without this, {@code d >= today} kept returning the just-expired date
     * for the rest of the calendar day, and Agent 2 rejected every recommendation with DTE=0.
     * <p>
     * Returns null if no upcoming date is found (empty cache + Upstox unavailable).
     */
    public LocalDate nextExpiry() {
        return resolveNextExpiry(allExpiries(), ZonedDateTime.now(IST));
    }

    /**
     * Pure expiry-roll logic — package-private so the cutoff/rollover can be unit-tested
     * deterministically without depending on the wall clock.
     * Expiry day is included only until 15:30 IST; after that it is skipped.
     */
    static LocalDate resolveNextExpiry(List<LocalDate> all, ZonedDateTime nowIst) {
        LocalDate today = nowIst.toLocalDate();
        boolean pastCutoff = nowIst.toLocalTime().isAfter(EXPIRY_CUTOFF);
        return all.stream()
                .filter(d -> d.isAfter(today) || (d.isEqual(today) && !pastCutoff))
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
                    SELECT value::text AS value, fetched_at, ttl_hours
                    FROM reference_data
                    WHERE key = ?
                      AND fetched_at + (ttl_hours * interval '1 hour') > NOW()
                    """,
                    CACHE_KEY);

            // value is a jsonb column — the pg driver returns it as a PGobject via queryForMap, so we
            // cast it to text in SQL (::text) and read it back as the JSON string.
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
