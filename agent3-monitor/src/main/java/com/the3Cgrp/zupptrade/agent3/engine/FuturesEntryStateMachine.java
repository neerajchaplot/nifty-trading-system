package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient.IntradayCandle;
import com.the3Cgrp.zupptrade.shared.enums.FuturePlanStatus;
import com.the3Cgrp.zupptrade.shared.enums.TradeDirection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Entry state machine for a single futures arm (spec §3), computed statelessly from the day's
 * completed 5-min candles each tick — so it is restart-safe and fully unit-testable.
 *
 * <pre>
 * Acceptance = candle CLOSE beyond the trigger (never a wick).
 *   LONG  arm → close &gt; entry ; SHORT arm → close &lt; entry.
 * Persistence = {@code requiredConsecutive} consecutive closes beyond (default 2).
 * Invalidate  = a close back inside AFTER a break.
 * Expire      = past the cutoff (~11:00) with no confirmation.
 * </pre>
 *
 * States returned: CONFIRMED (fire) | INVALIDATED | EXPIRED | BREAK_DETECTED | ARMED.
 * Only completed candles should be passed in (the caller drops the in-progress one).
 */
@Component
public class FuturesEntryStateMachine {

    public EntryDecision evaluate(List<IntradayCandle> candles, BigDecimal entry,
                                  TradeDirection direction, int requiredConsecutive, boolean pastCutoff) {
        boolean everBroke = false;
        int consecutive = 0;

        for (IntradayCandle c : candles) {
            if (beyond(c.close(), entry, direction)) {
                consecutive++;
                everBroke = true;
                if (consecutive >= requiredConsecutive) {
                    return EntryDecision.of(FuturePlanStatus.CONFIRMED,
                            requiredConsecutive + " consecutive closes beyond " + entry.toPlainString());
                }
            } else {
                // A close back inside after a prior break invalidates the setup.
                if (everBroke) {
                    return EntryDecision.of(FuturePlanStatus.INVALIDATED,
                            "Closed back inside " + entry.toPlainString() + " after a break");
                }
                consecutive = 0;
            }
        }

        if (pastCutoff) {
            return EntryDecision.of(FuturePlanStatus.EXPIRED, "No confirmed entry before cutoff");
        }
        return everBroke
                ? EntryDecision.of(FuturePlanStatus.BREAK_DETECTED, "One close beyond " + entry.toPlainString() + "; awaiting confirmation")
                : EntryDecision.of(FuturePlanStatus.ARMED, "Waiting for entry trigger");
    }

    /** LONG → close strictly above entry; SHORT → close strictly below entry. */
    private boolean beyond(BigDecimal close, BigDecimal entry, TradeDirection direction) {
        return direction == TradeDirection.LONG
                ? close.compareTo(entry) > 0
                : close.compareTo(entry) < 0;
    }
}
