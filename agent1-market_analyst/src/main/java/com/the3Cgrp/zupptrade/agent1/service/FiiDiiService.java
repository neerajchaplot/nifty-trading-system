package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.client.UpstoxFiiDiiClient;
import com.the3Cgrp.zupptrade.agent1.client.UpstoxFiiDiiClient.MarketFlowEntry;
import com.the3Cgrp.zupptrade.agent1.domain.entity.FiiDiiSnapshotEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiData;
import com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiTrend;
import com.the3Cgrp.zupptrade.agent1.repository.FiiDiiSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Orchestrates FII/DII data flow for every Agent 1 scoring run:
 *   1. Fetch all entries in the 7-day window from Upstox (via UpstoxFiiDiiClient)
 *   2. Persist any new daily snapshots to fii_dii_snapshots (skip if already stored)
 *   3. Compute 5-day trend from DB (post-persistence, so today's data is included)
 *   4. Return FiiDiiData with the latest values + trend for Tier 2 scoring
 *
 * Persistence failures are logged and swallowed — live data is still used for scoring.
 * Trend is UNAVAILABLE on the first run (no DB history yet); scoring is unaffected.
 */
@Service
public class FiiDiiService {

    private static final Logger log = LoggerFactory.getLogger(FiiDiiService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final UpstoxFiiDiiClient client;
    private final FiiDiiSnapshotRepository repository;

    public FiiDiiService(UpstoxFiiDiiClient client, FiiDiiSnapshotRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    /**
     * Fetch → persist → trend → return.
     * Returns null if all three Upstox segments returned empty data (token expired).
     */
    public FiiDiiData fetchAndPersist() {
        UpstoxFiiDiiClient.FiiDiiRawFetch raw = client.fetchAllEntries();
        if (raw == null) return null;

        // --- Step 2: persist new snapshots ---
        try {
            List<FiiDiiSnapshotEntity> candidates = buildEntities(raw);
            List<FiiDiiSnapshotEntity> newEntries = candidates.stream()
                    .filter(e -> !repository.existsByTradingDateAndSegment(
                            e.getTradingDate(), e.getSegment()))
                    .toList();
            if (!newEntries.isEmpty()) {
                repository.saveAll(newEntries);
                log.info("fii.dii.snapshots.persisted", kv("count", newEntries.size()));
            } else {
                log.debug("fii.dii.snapshots.already_current — no new rows to persist");
            }
        } catch (Exception ex) {
            // Non-fatal: live data still usable for scoring even if persistence fails
            log.warn("fii.dii.snapshots.persist_failed", kv("error", ex.getMessage()));
        }

        // --- Step 3: latest entry per segment for scoring ---
        MarketFlowEntry latestFutures = first(raw.futuresEntries());
        MarketFlowEntry latestOptions = first(raw.optionsEntries());
        MarketFlowEntry latestDii     = first(raw.diiEntries());

        BigDecimal fiiNetFutures = UpstoxFiiDiiClient.netFlow(latestFutures);
        BigDecimal fiiNetOptions = UpstoxFiiDiiClient.netFlow(latestOptions);
        BigDecimal diiNet        = UpstoxFiiDiiClient.netFlow(latestDii);
        BigDecimal fiiLongRatio  = UpstoxFiiDiiClient.computeLongRatio(latestFutures);

        if (fiiNetFutures == null && fiiNetOptions == null && diiNet == null) {
            log.warn("fii.dii.all_null — all segments empty after fetch");
            return null;
        }

        // --- Step 4: 5-day trend from DB (includes today's row just persisted) ---
        FiiDiiTrend trend = computeTrend();

        log.info("fii.dii.scored",
                kv("fiiNetFutures", fiiNetFutures),
                kv("fiiNetOptions", fiiNetOptions),
                kv("diiNet", diiNet),
                kv("fiiLongRatio", fiiLongRatio),
                kv("trendDirection", trend.direction()),
                kv("trendDaysPositive", trend.daysPositive()),
                kv("trendSnapshots", trend.snapshotCount()));

        return new FiiDiiData(fiiNetFutures, fiiNetOptions, diiNet, fiiLongRatio, trend);
    }

    // -------------------------------------------------------------------------
    // Trend calculation
    // -------------------------------------------------------------------------

    /**
     * Reads the last 5 INDEX_FUTURES snapshots from DB and classifies the trend.
     *
     * IMPROVING     — FII net futures was positive on ≥4 of the last 5 trading days
     * DETERIORATING — FII net futures was negative on ≥4 of the last 5 trading days
     * FLAT          — mixed or <3 snapshots available
     * UNAVAILABLE   — no snapshots at all (first run)
     */
    private FiiDiiTrend computeTrend() {
        List<FiiDiiSnapshotEntity> last5 = repository
                .findTop5BySegmentOrderByTradingDateDesc(UpstoxFiiDiiClient.DATA_TYPE_FII_FUTURES);

        if (last5.isEmpty()) return FiiDiiTrend.unavailable();

        int daysPositive = 0;
        int daysNegative = 0;
        BigDecimal sumNetFlow = BigDecimal.ZERO;
        int counted = 0;

        for (FiiDiiSnapshotEntity snap : last5) {
            BigDecimal nf = snap.getNetFlow();
            if (nf == null) continue;
            sumNetFlow = sumNetFlow.add(nf);
            counted++;
            if (nf.compareTo(BigDecimal.ZERO) > 0) daysPositive++;
            else if (nf.compareTo(BigDecimal.ZERO) < 0) daysNegative++;
        }

        BigDecimal avgNetFlow = counted > 0
                ? sumNetFlow.divide(BigDecimal.valueOf(counted), 2, RoundingMode.HALF_UP)
                : null;

        String direction;
        if (counted < 3) {
            direction = "FLAT";          // not enough data for a meaningful trend
        } else if (daysPositive >= 4) {
            direction = "IMPROVING";
        } else if (daysNegative >= 4) {
            direction = "DETERIORATING";
        } else {
            direction = "FLAT";
        }

        return new FiiDiiTrend(direction, avgNetFlow, daysPositive, daysNegative, last5.size());
    }

    // -------------------------------------------------------------------------
    // Entity builders
    // -------------------------------------------------------------------------

    private List<FiiDiiSnapshotEntity> buildEntities(UpstoxFiiDiiClient.FiiDiiRawFetch raw) {
        List<FiiDiiSnapshotEntity> entities = new ArrayList<>();
        toEntities(entities, raw.futuresEntries(), UpstoxFiiDiiClient.DATA_TYPE_FII_FUTURES);
        toEntities(entities, raw.optionsEntries(), UpstoxFiiDiiClient.DATA_TYPE_FII_OPTIONS);
        toEntities(entities, raw.diiEntries(),     UpstoxFiiDiiClient.DATA_TYPE_DII_CASH);
        return entities;
    }

    private void toEntities(List<FiiDiiSnapshotEntity> result,
                            List<MarketFlowEntry> entries,
                            String segment) {
        for (MarketFlowEntry entry : entries) {
            if (entry.timeStamp() == null) continue;

            LocalDate tradingDate = Instant.ofEpochMilli(entry.timeStamp())
                    .atZone(IST).toLocalDate();

            FiiDiiSnapshotEntity e = new FiiDiiSnapshotEntity();
            e.setTradingDate(tradingDate);
            e.setSegment(segment);
            e.setBuyAmount(entry.buyAmount());
            e.setSellAmount(entry.sellAmount());
            e.setNetFlow(UpstoxFiiDiiClient.netFlow(entry));
            e.setBuyContracts(entry.buyContracts());
            e.setSellContracts(entry.sellContracts());
            e.setOiContracts(entry.oiContracts());
            e.setOiAmount(entry.oiAmount());
            e.setTotalLongContracts(entry.totalLongContracts());
            e.setTotalShortContracts(entry.totalShortContracts());
            e.setLongRatio(UpstoxFiiDiiClient.computeLongRatio(entry));
            e.setFetchedAt(LocalDateTime.now());
            result.add(e);
        }
    }

    private static MarketFlowEntry first(List<MarketFlowEntry> entries) {
        return entries.isEmpty() ? null : entries.get(0);
    }
}
