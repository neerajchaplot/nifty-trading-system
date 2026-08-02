package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient.IntradayCandle;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import com.the3Cgrp.zupptrade.shared.enums.TradeDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entry FSM (spec §3) on a LONG rotation arm with entry (L3 reclaim) = 24280.
 * "Beyond" for a long = close above 24280; 2 consecutive closes confirm.
 */
class FuturesEntryStateMachineTest {

    private final FuturesEntryStateMachine fsm = new FuturesEntryStateMachine();

    private static final BigDecimal ENTRY = new BigDecimal("24280");
    private static final int REQUIRED = 2;

    private IntradayCandle candle(int minute, String close) {
        OffsetDateTime t = OffsetDateTime.of(2026, 8, 3, 9, minute, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        BigDecimal c = new BigDecimal(close);
        return new IntradayCandle(t, c, c, c, c, 0L);
    }

    @Test
    void twoConsecutiveClosesBeyond_confirmsAndFires() {
        List<IntradayCandle> candles = List.of(
                candle(15, "24270"),  // inside
                candle(20, "24285"),  // 1st close beyond
                candle(25, "24290")); // 2nd close beyond → CONFIRM
        EntryDecision d = fsm.evaluate(candles, ENTRY, TradeDirection.LONG, REQUIRED, false);
        assertThat(d.state()).isEqualTo(FuturePlanStatus.CONFIRMED);
        assertThat(d.fire()).isTrue();
    }

    @Test
    void oneCloseBeyond_isBreakDetected_notYetFired() {
        List<IntradayCandle> candles = List.of(
                candle(15, "24270"),
                candle(20, "24285")); // only 1 beyond
        EntryDecision d = fsm.evaluate(candles, ENTRY, TradeDirection.LONG, REQUIRED, false);
        assertThat(d.state()).isEqualTo(FuturePlanStatus.BREAK_DETECTED);
        assertThat(d.fire()).isFalse();
    }

    @Test
    void breakThenCloseBackInside_invalidates() {
        List<IntradayCandle> candles = List.of(
                candle(20, "24285"),  // break
                candle(25, "24275")); // back inside → INVALIDATED
        EntryDecision d = fsm.evaluate(candles, ENTRY, TradeDirection.LONG, REQUIRED, false);
        assertThat(d.state()).isEqualTo(FuturePlanStatus.INVALIDATED);
    }

    @Test
    void noBreakBeforeCutoff_expires() {
        List<IntradayCandle> candles = List.of(
                candle(15, "24270"),
                candle(20, "24260"));
        EntryDecision d = fsm.evaluate(candles, ENTRY, TradeDirection.LONG, REQUIRED, true);
        assertThat(d.state()).isEqualTo(FuturePlanStatus.EXPIRED);
    }

    @Test
    void noBreakBeforeCutoff_staysArmed() {
        List<IntradayCandle> candles = List.of(candle(15, "24270"));
        EntryDecision d = fsm.evaluate(candles, ENTRY, TradeDirection.LONG, REQUIRED, false);
        assertThat(d.state()).isEqualTo(FuturePlanStatus.ARMED);
    }

    @Test
    void shortArm_beyondMeansCloseBelowEntry() {
        // SHORT rotation entry (H3 reject) = 24357; beyond = close below 24357.
        BigDecimal shortEntry = new BigDecimal("24357");
        List<IntradayCandle> candles = List.of(
                candle(15, "24360"),  // inside (above)
                candle(20, "24350"),  // 1st below
                candle(25, "24345")); // 2nd below → CONFIRM
        EntryDecision d = fsm.evaluate(candles, shortEntry, TradeDirection.SHORT, REQUIRED, false);
        assertThat(d.state()).isEqualTo(FuturePlanStatus.CONFIRMED);
    }

    @Test
    void wickBeyondButCloseInside_doesNotCount() {
        // High pierces entry but close stays inside → not "beyond" (close-based acceptance).
        OffsetDateTime t = OffsetDateTime.of(2026, 8, 3, 9, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        IntradayCandle wick = new IntradayCandle(t,
                new BigDecimal("24275"), new BigDecimal("24300"),  // high 24300 > entry
                new BigDecimal("24270"), new BigDecimal("24278"),  // close 24278 < entry
                0L);
        EntryDecision d = fsm.evaluate(List.of(wick), ENTRY, TradeDirection.LONG, REQUIRED, false);
        assertThat(d.state()).isEqualTo(FuturePlanStatus.ARMED);
    }
}
