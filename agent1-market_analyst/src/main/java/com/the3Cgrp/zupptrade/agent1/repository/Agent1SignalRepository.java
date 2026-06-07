package com.the3Cgrp.zupptrade.agent1.repository;

import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface Agent1SignalRepository extends JpaRepository<Agent1SignalEntity, UUID> {

    Optional<Agent1SignalEntity> findTopByExpiryDateAndStatusOrderByTimestampDesc(LocalDate expiryDate, String status);

    /**
     * Returns the single most recently recorded ACTIVE signal across all expiry dates.
     * Used by ScoringPipeline to retrieve the previous session's VIX level for
     * vix_daily_change calculation in Tier 3 (VolatilityMacroScorer).
     */
    Optional<Agent1SignalEntity> findTopByStatusOrderByTimestampDesc(String status);
}
