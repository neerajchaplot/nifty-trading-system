package com.the3Cgrp.zupptrade.agent1.client;

import com.the3Cgrp.zupptrade.agent1.domain.model.OhlcCandle;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxHistoricalDataClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Agent1 adapter over core-module's UpstoxHistoricalDataClient.
 * Maps UpstoxCandle → OhlcCandle (agent1 domain type used by TechnicalIndicatorService).
 */
@Component
public class UpstoxHistoricalClient {

    private static final String VIX_KEY = "NSE_INDEX|India VIX";

    private final UpstoxHistoricalDataClient coreClient;

    public UpstoxHistoricalClient(UpstoxHistoricalDataClient coreClient) {
        this.coreClient = coreClient;
    }

    public List<OhlcCandle> fetchDailyCandles(int days) {
        return coreClient.fetchNiftyDailyCandles(days).stream()
                .map(c -> new OhlcCandle(c.date(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                .toList();
    }

    /**
     * Returns the most recent completed session's VIX closing price.
     * During market hours this is yesterday's close; after market close it is today's close.
     * Used as vixPrevLevel for the Tier 3 vix_daily_change signal.
     * Returns null if Upstox cannot serve the data.
     */
    public BigDecimal fetchVixPrevClose() {
        return coreClient.fetchLastClose(VIX_KEY);
    }
}
