package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExecutionChapter(
        EntryExecution entry,
        ExitExecution exit,
        BigDecimal totalSlippageAmount

) {
    public record EntryExecution(
            LocalDateTime placedAt,
            LocalDateTime executedAt,
            BigDecimal requestedNetPremium,
            BigDecimal actualNetPremium,
            BigDecimal slippageAmount,
            BigDecimal slippagePct,
            int requestedLots,
            int filledLots,
            String brokerStatus
    ) {}

    public record ExitExecution(
            LocalDateTime placedAt,
            LocalDateTime executedAt,
            BigDecimal actualNetPremium,
            int filledLots,
            String brokerStatus,
            String failureReason
    ) {}
}
