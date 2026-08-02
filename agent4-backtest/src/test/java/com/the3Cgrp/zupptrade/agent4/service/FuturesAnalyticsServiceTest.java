package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.FuturesPnlResponse;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.FuturesPnlRowDto;
import com.the3Cgrp.zupptrade.agent4.repository.FuturesAnalyticsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FuturesAnalyticsServiceTest {

    private final FuturesAnalyticsRepository repo = mock(FuturesAnalyticsRepository.class);
    private final FuturesAnalyticsService service = new FuturesAnalyticsService(repo);

    private FuturesPnlRowDto row(String code, String pnl) {
        return new FuturesPnlRowDto(code, LocalDate.now(), "LONG_ROTATION", "LONG",
                new BigDecimal("24280"), new BigDecimal("24237"), new BigDecimal("24357"),
                new BigDecimal(pnl), "Target hit", "BULLISH", "HIGH");
    }

    @Test
    void countsWinsLosses_andNetTotal() {
        when(repo.findClosed(any(), any())).thenReturn(List.of(
                row("FUT-1", "5005"), row("FUT-2", "-2795"), row("FUT-3", "5200")));

        FuturesPnlResponse res = service.getFuturesPnl(null, null);

        assertThat(res.summary().tradeCount()).isEqualTo(3);
        assertThat(res.summary().winCount()).isEqualTo(2);
        assertThat(res.summary().lossCount()).isEqualTo(1);
        assertThat(res.summary().totalRealizedPnl()).isEqualByComparingTo("7410"); // 5005 - 2795 + 5200
        assertThat(res.trades()).hasSize(3);
    }

    @Test
    void emptyWhenNoClosedTrades() {
        when(repo.findClosed(any(), any())).thenReturn(List.of());
        FuturesPnlResponse res = service.getFuturesPnl(null, null);
        assertThat(res.summary().tradeCount()).isZero();
        assertThat(res.summary().totalRealizedPnl()).isEqualByComparingTo("0");
        assertThat(res.trades()).isEmpty();
    }
}
