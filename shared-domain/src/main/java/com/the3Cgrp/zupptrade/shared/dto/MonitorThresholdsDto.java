package com.the3Cgrp.zupptrade.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * T1/T2/T3 Nifty levels and P&L thresholds for a monitored trade.
 *
 * Two-leg spreads (BullPutSpread, BearCallSpread): use t1WatchNiftyLevel, t2ReadjustNiftyLevel,
 *   t3ExitNiftyLevel — directional IC fields are null and omitted from JSON.
 *
 * Iron Condor: uses directional fields for each side. Single-direction fields are null.
 *   "Down" fields = Nifty levels where Nifty falling threatens the PE short.
 *   "Up"   fields = Nifty levels where Nifty rising threatens the CE short.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonitorThresholdsDto(
        BigDecimal t1WatchNiftyLevel,    // 2-leg: Nifty level that triggers T1 watch
        BigDecimal t2ReadjustNiftyLevel, // 2-leg: Nifty level that triggers T2 readjust
        BigDecimal t3ExitNiftyLevel,     // 2-leg: Nifty level that triggers T3 exit (= short strike)
        BigDecimal t2LossThreshold,      // All: 50% of max loss in Rs — triggers T2 readjust
        BigDecimal t3LossThreshold,      // All: 100% of max loss in Rs — triggers T3 exit

        // Iron Condor — put side (Nifty falls toward PE short strike)
        BigDecimal t1WatchNiftyDown,
        BigDecimal t2ReadjustNiftyDown,
        BigDecimal t3ExitNiftyDown,

        // Iron Condor — call side (Nifty rises toward CE short strike)
        BigDecimal t1WatchNiftyUp,
        BigDecimal t2ReadjustNiftyUp,
        BigDecimal t3ExitNiftyUp
) {

    /** Factory for Bull Put Spread / Bear Call Spread / Bull Call Spread (2-leg). */
    public static MonitorThresholdsDto twoLeg(BigDecimal t1Watch, BigDecimal t2Readjust,
                                               BigDecimal t3Exit,
                                               BigDecimal t2Loss, BigDecimal t3Loss) {
        return new MonitorThresholdsDto(t1Watch, t2Readjust, t3Exit, t2Loss, t3Loss,
                null, null, null, null, null, null);
    }

    /** Factory for Iron Condor — both PE (down) and CE (up) directional thresholds. */
    public static MonitorThresholdsDto ironCondor(
            BigDecimal t1WatchDown, BigDecimal t2ReadjustDown, BigDecimal t3ExitDown,
            BigDecimal t1WatchUp,   BigDecimal t2ReadjustUp,   BigDecimal t3ExitUp,
            BigDecimal t2Loss, BigDecimal t3Loss) {
        return new MonitorThresholdsDto(null, null, null, t2Loss, t3Loss,
                t1WatchDown, t2ReadjustDown, t3ExitDown,
                t1WatchUp,   t2ReadjustUp,   t3ExitUp);
    }
}
