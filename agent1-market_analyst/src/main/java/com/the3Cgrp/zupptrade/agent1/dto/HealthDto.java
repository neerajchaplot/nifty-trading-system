package com.the3Cgrp.zupptrade.agent1.dto;

import java.time.LocalDateTime;

public record HealthDto(
        String status,
        LocalDateTime lastRun,
        String lastBias,
        String lastConfidence
) {}
