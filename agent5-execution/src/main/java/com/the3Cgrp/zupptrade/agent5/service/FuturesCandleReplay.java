package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxIntradayCandleClient.IntradayCandle;
import com.the3Cgrp.zupptrade.shared.enums.TradeDirection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Simulated exit resolution for a futures position (paper trade). Walks the day's 5-min candles
 * from the fill time and determines where the broker's GTT OCO would have exited:
 *   - target hit  (LONG: high ≥ target ; SHORT: low ≤ target)
 *   - stop hit    (LONG: low ≤ stop    ; SHORT: high ≥ stop)
 *   - square-off  (neither touched by close → exit at the last bar's close, intraday auto-square)
 *
 * Stop takes priority when a single 5-min bar's range spans BOTH levels (conservative — we can't
 * know which was hit first within the bar). The first bar (chronologically) that touches a level wins.
 */
@Component
public class FuturesCandleReplay {

    public record ReplayResult(BigDecimal exitPrice, String closeReason, BigDecimal realizedPnl) {}

    public ReplayResult replay(List<IntradayCandle> dayCandles, BigDecimal entry, BigDecimal target,
                               BigDecimal stop, TradeDirection direction, OffsetDateTime fillTime, long quantity) {
        List<IntradayCandle> after = dayCandles.stream()
                .filter(c -> !c.time().isBefore(fillTime))   // from the fill candle onward
                .toList();

        BigDecimal exit = null;
        String reason = null;
        for (IntradayCandle c : after) {
            boolean stopHit = direction == TradeDirection.LONG
                    ? c.low().compareTo(stop) <= 0
                    : c.high().compareTo(stop) >= 0;
            boolean targetHit = direction == TradeDirection.LONG
                    ? c.high().compareTo(target) >= 0
                    : c.low().compareTo(target) <= 0;
            if (stopHit) { exit = stop; reason = "Stop hit"; break; }       // stop priority within a bar
            if (targetHit) { exit = target; reason = "Target hit"; break; }
        }

        if (exit == null) {
            // No level touched all day → square off at the last available close.
            exit = after.isEmpty()
                    ? (dayCandles.isEmpty() ? entry : dayCandles.get(dayCandles.size() - 1).close())
                    : after.get(after.size() - 1).close();
            reason = "Square-off";
        }

        BigDecimal pnl = (direction == TradeDirection.LONG
                ? exit.subtract(entry)
                : entry.subtract(exit))
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);

        return new ReplayResult(exit, reason, pnl);
    }
}
