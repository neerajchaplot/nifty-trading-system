package com.the3Cgrp.zupptrade.agent3.config;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the futures entry-window defaults (entries allowed until 14:30 IST, 1h before close). */
class FuturesEntryPropertiesTest {

    @Test
    void defaults_entryCutoffIs1430_enabledIst() {
        FuturesEntryProperties p = new FuturesEntryProperties();

        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getCutoffTime()).isEqualTo(LocalTime.of(14, 30));
        assertThat(p.getZone()).isEqualTo("Asia/Kolkata");
        assertThat(p.getRequiredConsecutiveCloses()).isEqualTo(2);
        assertThat(p.getCandleIntervalMinutes()).isEqualTo(5);
    }
}
