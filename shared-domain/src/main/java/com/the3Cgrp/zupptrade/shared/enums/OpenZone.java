package com.the3Cgrp.zupptrade.shared.enums;

/**
 * Open classification (spec §2.2): openPx = prevClose + giftPts, positioned against Camarilla bands.
 *   BREAKOUT   — open at/above H3 (stretched to the upper band)   → breakout arms
 *   RANGE      — open inside L3–H3 (mid-range)                     → rotation arms
 *   BREAKDOWN  — open at/below L3 (stretched to the lower band)    → breakdown arms
 *
 * The open location collapses the two bias-filtered arms down to one primary (spec §2.4):
 * rotation when open is mid-range, breakout/breakdown when open is stretched to a band.
 */
public enum OpenZone {
    BREAKOUT,
    RANGE,
    BREAKDOWN
}
