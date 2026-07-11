package com.the3Cgrp.zupptrade.agentUser.repository;

import com.the3Cgrp.zupptrade.agentUser.domain.UserProfileAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserProfileAuditRepository extends JpaRepository<UserProfileAuditEntity, UUID> {

    List<UserProfileAuditEntity> findTop50ByUserProfileIdOrderByChangedAtDesc(UUID userProfileId);
}
