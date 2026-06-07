package com.the3Cgrp.zupptrade.agent2.repository;

import com.the3Cgrp.zupptrade.agent2.domain.entity.TradeEntity;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TradeRepository extends JpaRepository<TradeEntity, UUID> {

    List<TradeEntity> findByExpiryDateAndStatus(LocalDate expiryDate, TradeStatus status);

    List<TradeEntity> findByStatus(TradeStatus status);

    @Query(value = "SELECT nextval('trade_code_seq')", nativeQuery = true)
    Long nextTradeCodeSeq();
}
