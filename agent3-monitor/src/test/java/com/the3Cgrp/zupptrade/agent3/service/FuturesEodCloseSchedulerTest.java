package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.client.Agent5FuturesCloseClient;
import com.the3Cgrp.zupptrade.agent3.config.FuturesEntryProperties;
import com.the3Cgrp.zupptrade.agent3.service.FuturesPlanReader.FuturesPlanRow;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FuturesEodCloseSchedulerTest {

    private FuturesPlanReader reader;
    private Agent5FuturesCloseClient closeClient;
    private FuturesEodCloseScheduler scheduler;

    @BeforeEach
    void setUp() {
        reader = mock(FuturesPlanReader.class);
        closeClient = mock(Agent5FuturesCloseClient.class);
        Environment env = mock(Environment.class);
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        scheduler = new FuturesEodCloseScheduler(reader, closeClient, new FuturesEntryProperties(), env);
    }

    private FuturesPlanRow filled(String code) {
        return new FuturesPlanRow(UUID.randomUUID(), code, "LONG_ROTATION",
                new BigDecimal("24280"), FuturePlanStatus.FILLED);
    }

    @Test
    void closesEveryFilledPlan() {
        FuturesPlanRow a = filled("FUT-1");
        FuturesPlanRow b = filled("FUT-2");
        when(reader.findFilledToday()).thenReturn(List.of(a, b));
        when(closeClient.close(any())).thenReturn(Optional.of("CLOSED"));

        scheduler.runCycleOnce();

        verify(closeClient).close(a.id());
        verify(closeClient).close(b.id());
    }

    @Test
    void noFilledPlans_doesNothing() {
        when(reader.findFilledToday()).thenReturn(List.of());
        scheduler.runCycleOnce();
        verify(closeClient, never()).close(any());
    }

    @Test
    void oneCloseFailing_doesNotStopTheRest() {
        FuturesPlanRow a = filled("FUT-1");
        FuturesPlanRow b = filled("FUT-2");
        when(reader.findFilledToday()).thenReturn(List.of(a, b));
        when(closeClient.close(a.id())).thenThrow(new RuntimeException("boom"));
        when(closeClient.close(b.id())).thenReturn(Optional.of("CLOSED"));

        scheduler.runCycleOnce();

        verify(closeClient).close(b.id()); // b still processed after a failed
    }
}
