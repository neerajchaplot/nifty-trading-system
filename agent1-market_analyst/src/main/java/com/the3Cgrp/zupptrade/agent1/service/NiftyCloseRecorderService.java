package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.domain.entity.NiftyDailyCloseEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.OhlcCandle;
import com.the3Cgrp.zupptrade.agent1.repository.NiftyDailyCloseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Persists settled Nifty 50 daily closes into nifty_daily_close from the candle list
 * Agent 1 already fetches for TA4J indicators. Read by Agent 4 to grade signal accuracy.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>Byproduct, not a new fetch</b> — operates on candles already in memory, so
 *       there is zero additional Upstox traffic.</li>
 *   <li><b>Settled closes only</b> — today's forming candle is skipped (its close is the
 *       live intraday value, not the settled close), mirroring the historical client's
 *       {@code fetchLastClose} behaviour.</li>
 *   <li><b>Best-effort</b> — recording is a side concern; any failure is logged and
 *       swallowed so it can never break a scoring run. The saveAll runs in its own
 *       transaction, so a failure here does not poison the caller.</li>
 *   <li><b>Idempotent</b> — existing dates are read once and skipped; settled closes
 *       never change, so insert-if-absent is sufficient.</li>
 * </ul>
 */
@Service
public class NiftyCloseRecorderService {

    private static final Logger log = LoggerFactory.getLogger(NiftyCloseRecorderService.class);

    private final NiftyDailyCloseRepository repository;

    public NiftyCloseRecorderService(NiftyDailyCloseRepository repository) {
        this.repository = repository;
    }

    /**
     * Upserts the settled closes from the given candles. Returns the number of new rows
     * written (0 on empty input or failure). Never throws.
     */
    public int record(List<OhlcCandle> candles) {
        if (candles == null || candles.isEmpty()) return 0;

        LocalDate today = LocalDate.now();
        try {
            // Only settled sessions (strictly before today) are authoritative.
            List<OhlcCandle> settled = candles.stream()
                    .filter(c -> c.date() != null && c.close() != null && c.date().isBefore(today))
                    .toList();
            if (settled.isEmpty()) return 0;

            LocalDate earliest = settled.stream()
                    .map(OhlcCandle::date)
                    .min(LocalDate::compareTo)
                    .orElse(today);
            Set<LocalDate> existing = repository.findExistingDatesFrom(earliest);

            List<NiftyDailyCloseEntity> toInsert = new ArrayList<>();
            for (OhlcCandle c : settled) {
                if (!existing.contains(c.date())) {
                    toInsert.add(new NiftyDailyCloseEntity(c.date(), c.close()));
                }
            }
            if (toInsert.isEmpty()) return 0;

            repository.saveAll(toInsert);
            log.info("nifty_close.recorded new_rows={} range=[{}..{}]",
                    toInsert.size(), earliest, today.minusDays(1));
            return toInsert.size();
        } catch (Exception e) {
            // Side concern — never break the scoring pipeline.
            log.warn("nifty_close.record_failed error={}", e.getMessage(), e);
            return 0;
        }
    }
}
