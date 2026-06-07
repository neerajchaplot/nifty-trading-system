package com.the3Cgrp.zupptrade.agent2.repository;

import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfileEntity, UUID> {
}
