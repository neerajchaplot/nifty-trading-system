package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.math.BigDecimal;

/** Minimal futures P&L summary — counts + net (kept deliberately simple). */
public record FuturesPnlSummaryDto(
        int tradeCount,
        int winCount,
        int lossCount,
        BigDecimal totalRealizedPnl
) {}
