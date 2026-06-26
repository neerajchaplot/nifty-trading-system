package com.the3Cgrp.zupptrade.agent4.calculator;

import java.math.BigDecimal;
import java.util.List;

/**
 * Computes maximum drawdown from a chronologically ordered list of trade P&L values.
 * Algorithm: walk forward tracking the running cumulative peak; the deepest trough
 * from any peak is the max drawdown. All arithmetic in BigDecimal.
 */
public final class DrawdownCalculator {

    private DrawdownCalculator() {}

    public record DrawdownResult(
            BigDecimal maxDrawdown,
            int consecutiveLossesInWorstRun,
            int totalLossTrades
    ) {}

    public static DrawdownResult compute(List<BigDecimal> orderedPnl) {
        if (orderedPnl == null || orderedPnl.isEmpty()) {
            return new DrawdownResult(BigDecimal.ZERO, 0, 0);
        }

        BigDecimal cumulativePeak    = BigDecimal.ZERO;
        BigDecimal runningTotal      = BigDecimal.ZERO;
        BigDecimal maxDrawdown       = BigDecimal.ZERO;

        int consecutiveLosses        = 0;
        int maxConsecutiveLosses     = 0;
        int totalLosses              = 0;

        for (BigDecimal pnl : orderedPnl) {
            if (pnl == null) continue;

            runningTotal = runningTotal.add(pnl);

            if (runningTotal.compareTo(cumulativePeak) > 0) {
                cumulativePeak = runningTotal;
            }

            BigDecimal drawdown = cumulativePeak.subtract(runningTotal);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }

            if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                consecutiveLosses++;
                totalLosses++;
                if (consecutiveLosses > maxConsecutiveLosses) {
                    maxConsecutiveLosses = consecutiveLosses;
                }
            } else {
                consecutiveLosses = 0;
            }
        }

        return new DrawdownResult(maxDrawdown, maxConsecutiveLosses, totalLosses);
    }
}
