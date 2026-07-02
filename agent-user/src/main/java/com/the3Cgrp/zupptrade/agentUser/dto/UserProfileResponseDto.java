package com.the3Cgrp.zupptrade.agentUser.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record UserProfileResponseDto(
        UUID id,
        String userId,
        BigDecimal capital
) {}
