package com.the3Cgrp.zupptrade.shared.enums;

public enum Strategy {
    BULL_PUT_SPREAD,   // credit — Bullish + Mild + VIX Normal/High + IV Rich
    BEAR_CALL_SPREAD,  // credit — Bearish + Mild/Extreme + VIX Normal/High + IV Rich
    BULL_CALL_SPREAD,  // debit  — Bullish + Extreme (any VIX) or Bullish + Mild + VIX Low
    BEAR_PUT_SPREAD,   // debit  — Bearish + Mild/Extreme + VIX Low
    IRON_CONDOR,       // credit — Neutral + Weak + VIX Normal + IV Rich
    WIDE_IRON_CONDOR,  // credit — Neutral + Weak + VIX High + IV Rich
    SHORT_STRADDLE,    // credit — Neutral + Weak + VIX Normal + IV Rich (alt to IC)
    SKIP,              // conditions met but a gate fails — log and skip
    NO_TRADE;          // VIX Extreme, or Confidence LOW on credit path, or known event exclusion

    public boolean isDebit() {
        return this == BULL_CALL_SPREAD || this == BEAR_PUT_SPREAD;
    }

    public boolean isCredit() {
        return this == BULL_PUT_SPREAD || this == BEAR_CALL_SPREAD
                || this == IRON_CONDOR || this == WIDE_IRON_CONDOR || this == SHORT_STRADDLE;
    }
}
