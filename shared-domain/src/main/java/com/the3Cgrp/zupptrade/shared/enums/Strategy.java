package com.the3Cgrp.zupptrade.shared.enums;

public enum Strategy {
    BULL_PUT_SPREAD,   // credit — Bullish + Strong/Mild + VIX High + IV Rich
    BEAR_CALL_SPREAD,  // credit — Bearish + Strong/Mild + VIX High + IV Rich
    BULL_CALL_SPREAD,  // debit  — Bullish + Extreme + any VIX
    IRON_CONDOR,       // credit — Neutral + Weak + VIX Normal + IV Rich
    WIDE_IRON_CONDOR,  // credit — Neutral + Weak + VIX High + IV Rich
    SHORT_STRADDLE,    // credit — Neutral + Weak + VIX Normal + IV Rich (alt to IC)
    SKIP,              // conditions met but RoC gate fails — log and skip
    NO_TRADE           // VIX Extreme, or Confidence LOW, or known event exclusion
}
