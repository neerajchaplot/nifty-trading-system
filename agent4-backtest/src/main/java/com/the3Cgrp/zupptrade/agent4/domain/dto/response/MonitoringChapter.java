package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record MonitoringChapter(
        List<MonitoringEventDto> events,
        int holdCount,
        int watchCount,
        int readjustCount,
        /** Which threshold or condition ultimately triggered the final exit, null if not yet exited. */
        String exitTriggeredBy,
        BigDecimal finalPnl
) {}
