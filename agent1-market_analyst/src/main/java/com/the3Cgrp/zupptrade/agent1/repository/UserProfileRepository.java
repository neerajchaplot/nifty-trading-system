package com.the3Cgrp.zupptrade.agent1.repository;

import com.the3Cgrp.zupptrade.agent1.domain.entity.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Read-only access to user_profiles for tier-weight resolution. */
public interface UserProfileRepository extends JpaRepository<UserProfileEntity, UUID> {

    Optional<UserProfileEntity> findByUserId(String userId);
}
