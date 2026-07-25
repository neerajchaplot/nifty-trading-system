package com.the3Cgrp.zupptrade.agent1.repository;

import com.the3Cgrp.zupptrade.agent1.domain.entity.NiftyDailyCloseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Set;

public interface NiftyDailyCloseRepository extends JpaRepository<NiftyDailyCloseEntity, LocalDate> {

    /**
     * Trade dates already stored on or after {@code from} — used by the recorder to
     * insert only the candles that are missing (existing settled closes never change).
     */
    @Query("SELECT e.tradeDate FROM NiftyDailyCloseEntity e WHERE e.tradeDate >= :from")
    Set<LocalDate> findExistingDatesFrom(@Param("from") LocalDate from);
}
