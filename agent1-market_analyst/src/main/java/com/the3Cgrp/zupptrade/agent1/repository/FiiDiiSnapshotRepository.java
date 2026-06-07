package com.the3Cgrp.zupptrade.agent1.repository;

import com.the3Cgrp.zupptrade.agent1.domain.entity.FiiDiiSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FiiDiiSnapshotRepository extends JpaRepository<FiiDiiSnapshotEntity, UUID> {

    /** Duplicate guard — checked before each insert. */
    boolean existsByTradingDateAndSegment(LocalDate tradingDate, String segment);

    /**
     * Last 5 trading sessions for a segment, newest first.
     * Used by FiiDiiService to compute the 5-day trend.
     */
    List<FiiDiiSnapshotEntity> findTop5BySegmentOrderByTradingDateDesc(String segment);

    /**
     * Date-range query for backtesting.
     * Returns all rows for a segment between from and to (inclusive), newest first.
     * Agent 4 (backtest) uses this to replay historical FII/DII inputs.
     */
    List<FiiDiiSnapshotEntity> findBySegmentAndTradingDateBetweenOrderByTradingDateDesc(
            String segment, LocalDate from, LocalDate to);
}
