package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One closed futures trade for the Agent 4 futures filter. {@code bias} + {@code confidenceLabel}
 * power the row-hover ("the Agent 1 recommendation"); {@code realizedPnl} is the color-coded +/− value.
 */
public record FuturesPnlRowDto(
        String planCode,
        LocalDate tradeDate,
        String armType,
        String direction,
        BigDecimal entry,
        BigDecimal stop,
        BigDecimal target,
        BigDecimal realizedPnl,
        String closeReason,
        String bias,
        String confidenceLabel
) {}
