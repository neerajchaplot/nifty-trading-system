package com.the3Cgrp.zupptrade.agent1.client;

import com.the3Cgrp.zupptrade.agent1.domain.model.OhlcCandle;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent1 adapter over core-module's UpstoxHistoricalDataClient.
 * Maps UpstoxCandle → OhlcCandle (agent1 domain type used by TechnicalIndicatorService).
 */
@Component
public class UpstoxHistoricalClient {

    private final UpstoxHistoricalDataClient coreClient;

    public UpstoxHistoricalClient(UpstoxHistoricalDataClient coreClient) {
        this.coreClient = coreClient;
    }

    public List<OhlcCandle> fetchDailyCandles(int days) {
        return coreClient.fetchNiftyDailyCandles(days).stream()
                .map(c -> new OhlcCandle(c.date(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                .toList();
    }
}
