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
 *
 * Credit ladder (June 2026): the Nifty levels are a snapshot; the durable driver is the seller
 * PoP at entry (entryPop / entryPopDown / entryPopUp). Agent 3 re-derives the 70/64/57-style target
 * PoPs from it via CreditLadderCalculator and recomputes the Nifty levels every cycle from live
 * IV + DTE. Storing entryPop (not the target PoPs) keeps the fractions in one place (the calculator)
 * so entry and monitor can never drift.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonitorThresholdsDto(
        BigDecimal t1WatchNiftyLevel,    // 2-leg: Nifty level that triggers T1 watch
        BigDecimal t2ReadjustNiftyLevel, // 2-leg: Nifty level that triggers T2 readjust
        BigDecimal t3ExitNiftyLevel,     // 2-leg: Nifty level that triggers T3 exit
        BigDecimal t2LossThreshold,      // All: Rs loss that triggers T2 readjust
        BigDecimal t3LossThreshold,      // All: Rs loss that triggers T3 exit

        // Iron Condor — put side (Nifty falls toward PE short strike)
        BigDecimal t1WatchNiftyDown,
        BigDecimal t2ReadjustNiftyDown,
        BigDecimal t3ExitNiftyDown,

        // Iron Condor — call side (Nifty rises toward CE short strike)
        BigDecimal t1WatchNiftyUp,
        BigDecimal t2ReadjustNiftyUp,
        BigDecimal t3ExitNiftyUp,

        // Seller PoP at entry — the durable input Agent 3 uses to recompute the credit ladder.
        // 2-leg: entryPop. Iron Condor: entryPopDown (PE short) + entryPopUp (CE short).
        BigDecimal entryPop,
        BigDecimal entryPopDown,
        BigDecimal entryPopUp,

        // Debit spreads only: directional PoP at the SHORT strike (probability of MAX profit) at entry.
        // Paired with entryPop (directional PoP at breakeven). Agent 3 uses the gap for the give-back
        // lock and entryPop for the disaster stop. For debit, t1=breakeven, t2=short strike (profit-book),
        // t3=long strike (max-loss ref).
        BigDecimal entryPopp
) {

    /** Factory for 2-leg spreads (no entry PoP — legacy). */
    public static MonitorThresholdsDto twoLeg(BigDecimal t1Watch, BigDecimal t2Readjust,
                                               BigDecimal t3Exit,
                                               BigDecimal t2Loss, BigDecimal t3Loss) {
        return new MonitorThresholdsDto(t1Watch, t2Readjust, t3Exit, t2Loss, t3Loss,
                null, null, null, null, null, null, null, null, null, null);
    }

    /** Factory for a 2-leg credit spread carrying the entry seller PoP (Agent 3 recompute source). */
    public static MonitorThresholdsDto twoLegCredit(BigDecimal t1Watch, BigDecimal t2Readjust,
                                                     BigDecimal t3Exit,
                                                     BigDecimal t2Loss, BigDecimal t3Loss,
                                                     BigDecimal entryPop) {
        return new MonitorThresholdsDto(t1Watch, t2Readjust, t3Exit, t2Loss, t3Loss,
                null, null, null, null, null, null, entryPop, null, null, null);
    }

    /**
     * Factory for a debit spread (Bull Call / Bear Put).
     * t1 = breakeven (ref), t2 = short strike (profit-book / max-profit), t3 = long strike (max-loss ref).
     * entryPop = directional PoP at breakeven; entryPopp = directional PoP at the short strike.
     */
    public static MonitorThresholdsDto debitSpread(BigDecimal breakeven, BigDecimal shortStrike,
                                                    BigDecimal longStrike,
                                                    BigDecimal t2Loss, BigDecimal t3Loss,
                                                    BigDecimal entryPop, BigDecimal entryPopp) {
        return new MonitorThresholdsDto(breakeven, shortStrike, longStrike, t2Loss, t3Loss,
                null, null, null, null, null, null, entryPop, null, null, entryPopp);
    }

    /** Factory for Iron Condor — both PE (down) and CE (up) directional thresholds (no entry PoP). */
    public static MonitorThresholdsDto ironCondor(
            BigDecimal t1WatchDown, BigDecimal t2ReadjustDown, BigDecimal t3ExitDown,
            BigDecimal t1WatchUp,   BigDecimal t2ReadjustUp,   BigDecimal t3ExitUp,
            BigDecimal t2Loss, BigDecimal t3Loss) {
        return new MonitorThresholdsDto(null, null, null, t2Loss, t3Loss,
                t1WatchDown, t2ReadjustDown, t3ExitDown,
                t1WatchUp,   t2ReadjustUp,   t3ExitUp,
                null, null, null, null);
    }

    /** Factory for Iron Condor carrying both sides' entry seller PoP (Agent 3 recompute source). */
    public static MonitorThresholdsDto ironCondorCredit(
            BigDecimal t1WatchDown, BigDecimal t2ReadjustDown, BigDecimal t3ExitDown,
            BigDecimal t1WatchUp,   BigDecimal t2ReadjustUp,   BigDecimal t3ExitUp,
            BigDecimal t2Loss, BigDecimal t3Loss,
            BigDecimal entryPopDown, BigDecimal entryPopUp) {
        return new MonitorThresholdsDto(null, null, null, t2Loss, t3Loss,
                t1WatchDown, t2ReadjustDown, t3ExitDown,
                t1WatchUp,   t2ReadjustUp,   t3ExitUp,
                null, entryPopDown, entryPopUp, null);
    }
}
