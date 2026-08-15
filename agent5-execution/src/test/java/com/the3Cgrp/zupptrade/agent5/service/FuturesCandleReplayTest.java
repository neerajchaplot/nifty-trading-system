package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.service.FuturesCandleReplay.ReplayResult;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient.IntradayCandle;
import com.the3Cgrp.zupptrade.shared.enums.TradeDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Candle-replay exit resolution. Long arm: entry 24280, target 24357, stop 24237, 65 qty.
 * Fill at 09:20; only candles at/after fill count.
 */
class FuturesCandleReplayTest {

    private final FuturesCandleReplay replay = new FuturesCandleReplay();

    private static final BigDecimal ENTRY = new BigDecimal("24280");
    private static final BigDecimal TARGET = new BigDecimal("24357");
    private static final BigDecimal STOP = new BigDecimal("24237");
    private static final OffsetDateTime FILL =
            OffsetDateTime.of(2026, 8, 3, 9, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

    /** OHLC candle at 09:`minute`. */
    private IntradayCandle c(int minute, String o, String h, String l, String close) {
        OffsetDateTime t = OffsetDateTime.of(2026, 8, 3, 9, minute, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        return new IntradayCandle(t, new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(close), 0L);
    }

    @Test
    void longTargetHit_winsAtTarget() {
        ReplayResult r = replay.replay(List.of(
                c(20, "24280", "24300", "24270", "24290"),
                c(25, "24290", "24360", "24285", "24355")), // high 24360 ≥ target
                ENTRY, TARGET, STOP, TradeDirection.LONG, FILL, 65);

        assertThat(r.closeReason()).isEqualTo("Target hit");
        assertThat(r.exitPrice()).isEqualByComparingTo("24357");
        assertThat(r.realizedPnl()).isEqualByComparingTo("5005.00"); // (24357-24280)*65
    }

    @Test
    void longStopHit_losesAtStop() {
        ReplayResult r = replay.replay(List.of(
                c(25, "24280", "24290", "24230", "24240")), // low 24230 ≤ stop
                ENTRY, TARGET, STOP, TradeDirection.LONG, FILL, 65);

        assertThat(r.closeReason()).isEqualTo("Stop hit");
        assertThat(r.exitPrice()).isEqualByComparingTo("24237");
        assertThat(r.realizedPnl()).isEqualByComparingTo("-2795.00"); // (24237-24280)*65
    }

    @Test
    void bothInOneBar_stopWins() {
        ReplayResult r = replay.replay(List.of(
                c(25, "24280", "24360", "24230", "24300")), // high≥target AND low≤stop
                ENTRY, TARGET, STOP, TradeDirection.LONG, FILL, 65);

        assertThat(r.closeReason()).isEqualTo("Stop hit");
        assertThat(r.realizedPnl()).isEqualByComparingTo("-2795.00");
    }

    @Test
    void noTouch_squaresOffAtLastClose() {
        ReplayResult r = replay.replay(List.of(
                c(20, "24280", "24300", "24270", "24290"),
                c(25, "24290", "24310", "24280", "24305")), // never reaches target/stop
                ENTRY, TARGET, STOP, TradeDirection.LONG, FILL, 65);

        assertThat(r.closeReason()).isEqualTo("Square-off");
        assertThat(r.exitPrice()).isEqualByComparingTo("24305");        // last close
        assertThat(r.realizedPnl()).isEqualByComparingTo("1625.00");    // (24305-24280)*65
    }

    @Test
    void ignoresCandlesBeforeFill() {
        ReplayResult r = replay.replay(List.of(
                c(15, "24200", "24360", "24200", "24350"), // BEFORE fill (09:15) — must be ignored
                c(25, "24290", "24300", "24285", "24295")),
                ENTRY, TARGET, STOP, TradeDirection.LONG, FILL, 65);

        assertThat(r.closeReason()).isEqualTo("Square-off"); // pre-fill target spike ignored
    }

    @Test
    void shortArm_targetIsBelow_stopIsAbove() {
        // SHORT: entry 24357, target 24277 (below), stop 24397 (above).
        BigDecimal entry = new BigDecimal("24357");
        BigDecimal target = new BigDecimal("24277");
        BigDecimal stop = new BigDecimal("24397");
        ReplayResult r = replay.replay(List.of(
                c(25, "24357", "24360", "24270", "24280")), // low 24270 ≤ target
                entry, target, stop, TradeDirection.SHORT, FILL, 65);

        assertThat(r.closeReason()).isEqualTo("Target hit");
        assertThat(r.realizedPnl()).isEqualByComparingTo("5200.00"); // (24357-24277)*65
    }
}
