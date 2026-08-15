package com.the3Cgrp.zupptrade.agentUser.repository;

import com.the3Cgrp.zupptrade.agentUser.domain.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfileEntity, UUID> {

    Optional<UserProfileEntity> findByUserId(String userId);

    /** Resolve a user by provider identity — the multi-user lookup (Google vs Upstox are distinct). */
    Optional<UserProfileEntity> findByAuthProviderAndUserId(String authProvider, String userId);

    /** The admin profile whose Upstox token backs shared market-data reads for simulation users. */
    Optional<UserProfileEntity> findFirstByAdminTrue();
}
