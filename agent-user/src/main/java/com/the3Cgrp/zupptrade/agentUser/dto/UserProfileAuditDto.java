package com.the3Cgrp.zupptrade.agentUser.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record UserProfileAuditDto(
        UUID id,
        LocalDateTime changedAt,
        Map<String, Object> oldValues,
        Map<String, Object> newValues
) {}
