package com.the3Cgrp.zupptrade.agent2.repository;

import com.the3Cgrp.zupptrade.agent2.domain.entity.Agent1SignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface Agent1SignalRepository extends JpaRepository<Agent1SignalEntity, UUID> {

    Optional<Agent1SignalEntity> findTopByExpiryDateAndStatusOrderByTimestampDesc(LocalDate expiryDate, String status);
}
