package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.client.Agent5FuturesClient;
import com.the3Cgrp.zupptrade.agent3.config.FuturesEntryProperties;
import com.the3Cgrp.zupptrade.agent3.engine.FuturesEntryStateMachine;
import com.the3Cgrp.zupptrade.agent3.service.FuturesPlanReader.FuturesPlanRow;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient.IntradayCandle;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Orchestration test for the futures entry scheduler — real FSM, mocked reader/candles/Agent5.
 * Clock fixed at 09:30 IST so the 09:20 & 09:25 candles are "completed".
 */
class FuturesEntrySchedulerTest {

    private FuturesPlanReader reader;
    private UpstoxIntradayCandleClient intradayClient;
    private Agent5FuturesClient agent5Client;
    private FuturesEntryScheduler scheduler;

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-03T04:00:00Z"), ZoneOffset.UTC); // 09:30 IST
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reader = mock(FuturesPlanReader.class);
        intradayClient = mock(UpstoxIntradayCandleClient.class);
        agent5Client = mock(Agent5FuturesClient.class);

        Environment env = mock(Environment.class);
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(false); // not simulation

        scheduler = new FuturesEntryScheduler(reader, new FuturesEntryStateMachine(),
                intradayClient, agent5Client, new FuturesEntryProperties(), clock, env);
    }

    private IntradayCandle candle(int minute, String close) {
        OffsetDateTime t = OffsetDateTime.of(2026, 8, 3, 9, minute, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        BigDecimal c = new BigDecimal(close);
        return new IntradayCandle(t, c, c, c, c, 0L);
    }

    private FuturesPlanRow armedLong() {
        return new FuturesPlanRow(planId, "FUT-20260803-001", "LONG_ROTATION",
                new BigDecimal("24280"), FuturePlanStatus.ARMED);
    }

    @Test
    void twoClosesBeyond_confirmsAndHandsToAgent5() {
        when(reader.findWatchable()).thenReturn(List.of(armedLong()));
        when(intradayClient.fetchNiftyIntradayCandles(5))
                .thenReturn(List.of(candle(20, "24285"), candle(25, "24290")));
        when(agent5Client.placeGtt(planId)).thenReturn(Optional.of("GTT-1"));

        scheduler.runCycleOnce();

        verify(agent5Client).placeGtt(planId);
        verify(reader).markConfirmed(planId, "GTT-1");
        verify(reader, never()).updateStatus(any(), any());
    }

    @Test
    void breakThenInside_marksInvalidated_noHandoff() {
        when(reader.findWatchable()).thenReturn(List.of(armedLong()));
        when(intradayClient.fetchNiftyIntradayCandles(5))
                .thenReturn(List.of(candle(20, "24285"), candle(25, "24275")));

        scheduler.runCycleOnce();

        verify(reader).updateStatus(planId, FuturePlanStatus.INVALIDATED);
        verify(agent5Client, never()).placeGtt(any());
        verify(reader, never()).markConfirmed(any(), any());
    }

    @Test
    void oneCloseBeyond_advancesToBreakDetected() {
        when(reader.findWatchable()).thenReturn(List.of(armedLong()));
        when(intradayClient.fetchNiftyIntradayCandles(5))
                .thenReturn(List.of(candle(25, "24285")));

        scheduler.runCycleOnce();

        verify(reader).updateStatus(planId, FuturePlanStatus.BREAK_DETECTED);
        verify(agent5Client, never()).placeGtt(any());
    }

    @Test
    void noWatchablePlans_skipsCandleFetch() {
        when(reader.findWatchable()).thenReturn(List.of());

        scheduler.runCycleOnce();

        verify(intradayClient, never()).fetchNiftyIntradayCandles(anyInt());
    }

    @Test
    void alreadyBreakDetected_stillBreakDetected_noRedundantWrite() {
        FuturesPlanRow row = new FuturesPlanRow(planId, "FUT-20260803-002", "LONG_ROTATION",
                new BigDecimal("24280"), FuturePlanStatus.BREAK_DETECTED);
        when(reader.findWatchable()).thenReturn(List.of(row));
        when(intradayClient.fetchNiftyIntradayCandles(5))
                .thenReturn(List.of(candle(25, "24285"))); // one beyond → BREAK_DETECTED again

        scheduler.runCycleOnce();

        verify(reader, never()).updateStatus(any(), any());
        verify(reader, never()).markConfirmed(any(), any());
    }
}
