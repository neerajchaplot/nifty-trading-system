package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;

/**
 * Plain-English trade labels for the UI — no engine jargon on screen (user requirement).
 */
final class FuturesLabels {

    private FuturesLabels() {}

    static String label(FutureArmType type) {
        return switch (type) {
            case LONG_ROTATION -> "Buy the dip";
            case SHORT_ROTATION -> "Sell the rise";
            case LONG_BREAKOUT -> "Breakout buy";
            case SHORT_BREAKDOWN -> "Breakdown sell";
        };
    }
}
