package com.the3Cgrp.zupptrade.agent1.repository;

import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.shared.enums.SignalSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface Agent1SignalRepository extends JpaRepository<Agent1SignalEntity, UUID> {

    Optional<Agent1SignalEntity> findTopByExpiryDateAndStatusOrderByTimestampDesc(LocalDate expiryDate, String status);

    /** Per-user scoped variant (Phase 5) — the latest ACTIVE signal this user generated for an expiry. */
    Optional<Agent1SignalEntity> findTopByExpiryDateAndUserProfileIdAndStatusOrderByTimestampDesc(
            LocalDate expiryDate, UUID userProfileId, String status);

    /** Global latest for a channel (used for FUTURES — the shared, admin-driven signal). */
    Optional<Agent1SignalEntity> findTopByExpiryDateAndSourceAndStatusOrderByTimestampDesc(
            LocalDate expiryDate, SignalSource source, String status);

    /** Per-user latest for a channel (used for TRADING — scoped to the acting user). */
    Optional<Agent1SignalEntity> findTopByExpiryDateAndUserProfileIdAndSourceAndStatusOrderByTimestampDesc(
            LocalDate expiryDate, UUID userProfileId, SignalSource source, String status);

    /**
     * Returns the single most recently recorded ACTIVE signal across all expiry dates.
     * Used by ScoringPipeline to retrieve the previous session's VIX level for
     * vix_daily_change calculation in Tier 3 (VolatilityMacroScorer).
     */
    Optional<Agent1SignalEntity> findTopByStatusOrderByTimestampDesc(String status);
}
