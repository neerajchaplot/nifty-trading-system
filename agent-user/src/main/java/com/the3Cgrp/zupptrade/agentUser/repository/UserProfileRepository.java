package com.the3Cgrp.zupptrade.agentUser.repository;

import com.the3Cgrp.zupptrade.agentUser.domain.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfileEntity, UUID> {

    Optional<UserProfileEntity> findByUserId(String userId);
}
