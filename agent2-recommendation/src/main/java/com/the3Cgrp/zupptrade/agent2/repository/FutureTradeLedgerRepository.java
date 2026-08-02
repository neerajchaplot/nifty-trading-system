package com.the3Cgrp.zupptrade.agent2.repository;

import com.the3Cgrp.zupptrade.agent2.domain.entity.FutureTradeLedgerEntity;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FutureTradeLedgerRepository extends JpaRepository<FutureTradeLedgerEntity, UUID> {

    /** Count of plans for a given trade date — used to build the daily plan_code sequence. */
    long countByTradeDate(LocalDate tradeDate);

    /** Kill-switch scope: how many plans a user already committed today. */
    long countByUserProfileIdAndTradeDateAndStatusIn(
            UUID userProfileId, LocalDate tradeDate, List<FuturePlanStatus> statuses);

    /** Screen 2: accepted (dormant + active) plans for a day, newest first. */
    List<FutureTradeLedgerEntity> findByTradeDateAndStatusInOrderByCreatedAtDesc(
            LocalDate tradeDate, List<FuturePlanStatus> statuses);
}
