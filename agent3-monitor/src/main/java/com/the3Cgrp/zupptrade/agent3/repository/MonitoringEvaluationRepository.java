package com.the3Cgrp.zupptrade.agent3.repository;

import com.the3Cgrp.zupptrade.agent3.domain.entity.MonitoringEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MonitoringEvaluationRepository extends JpaRepository<MonitoringEvaluationEntity, UUID> {

    /** Latest evaluation for a trade — used to check previous VIX and last action. */
    @Query("SELECT e FROM MonitoringEvaluationEntity e WHERE e.tradeId = :tradeId ORDER BY e.evaluatedAt DESC LIMIT 1")
    Optional<MonitoringEvaluationEntity> findLatestByTradeId(@Param("tradeId") UUID tradeId);
}
