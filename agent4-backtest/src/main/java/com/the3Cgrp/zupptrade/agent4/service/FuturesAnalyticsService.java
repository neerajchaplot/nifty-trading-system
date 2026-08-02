package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.FuturesPnlResponse;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.FuturesPnlRowDto;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.FuturesPnlSummaryDto;
import com.the3Cgrp.zupptrade.agent4.repository.FuturesAnalyticsRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Agent 4 futures P&L filter: closed futures trades + simple counts (trades / wins / losses / net).
 */
@Service
public class FuturesAnalyticsService {

    private final FuturesAnalyticsRepository repository;

    public FuturesAnalyticsService(FuturesAnalyticsRepository repository) {
        this.repository = repository;
    }

    public FuturesPnlResponse getFuturesPnl(LocalDate from, LocalDate to) {
        List<FuturesPnlRowDto> trades = repository.findClosed(from, to);

        int wins = 0, losses = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (FuturesPnlRowDto t : trades) {
            BigDecimal pnl = t.realizedPnl() != null ? t.realizedPnl() : BigDecimal.ZERO;
            total = total.add(pnl);
            if (pnl.signum() > 0) wins++;
            else if (pnl.signum() < 0) losses++;
        }
        FuturesPnlSummaryDto summary = new FuturesPnlSummaryDto(trades.size(), wins, losses, total);
        return new FuturesPnlResponse(summary, trades);
    }
}
