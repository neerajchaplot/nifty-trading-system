package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MonitoringEventDto(
        LocalDateTime evaluatedAt,
        String action,
        BigDecimal spotPrice,
        BigDecimal vixLevel,
        BigDecimal markToMarketPnl,
        BigDecimal currentPop,
        String thresholdHit,
        String reason
) {}
