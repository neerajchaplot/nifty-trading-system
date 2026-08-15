package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient.IntradayCandle;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Supplies today's Nifty 5-min candles for the EOD close cycle, fetched ONCE per trading day and
 * reused across every simulated close in that cycle (the 5-min series is identical for all trades
 * that day). Nothing is persisted — the candles are transient inputs to the replay.
 */
@Component
public class FuturesDayCandleProvider {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final UpstoxIntradayCandleClient intradayClient;

    private volatile LocalDate cachedFor;
    private volatile List<IntradayCandle> cached;

    public FuturesDayCandleProvider(UpstoxIntradayCandleClient intradayClient) {
        this.intradayClient = intradayClient;
    }

    /** Today's Nifty 5-min candles (oldest-first), fetched once per IST trading day. */
    public synchronized List<IntradayCandle> todayNifty5m() {
        LocalDate today = LocalDate.now(IST);
        if (today.equals(cachedFor) && cached != null) {
            return cached;
        }
        cached = intradayClient.fetchNiftyIntradayCandles(5);
        cachedFor = today;
        return cached;
    }
}
