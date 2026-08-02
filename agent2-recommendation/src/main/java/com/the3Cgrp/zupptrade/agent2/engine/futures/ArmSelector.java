package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmSelection;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.OpenZone;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Collapses the four-arm grid to one decision (spec §2.4):
 *   1. Bias collapses 4 → 2 (side filter).
 *   2. Open location collapses 2 → 1 (rotation if mid-range, breakout/breakdown if stretched).
 *
 * Special cases:
 *   - Neutral bias → range-fade both rotation arms (primary is null). The compression
 *     gate (§6.3), applied by the service, decides wide (fade) vs tight (NO_TRADE).
 *   - Bias vs open-location conflict (e.g. bullish bias but open broke DOWN) → NO_TRADE:
 *     there is no long-breakdown arm, so no clean primary survives.
 */
@Component
public class ArmSelector {

    public ArmSelection select(Bias bias, OpenZone openZone) {
        return switch (bias) {
            case BULLISH -> selectLong(openZone);
            case BEARISH -> selectShort(openZone);
            case NEUTRAL -> ArmSelection.rangeFade(
                    List.of(FutureArmType.LONG_ROTATION, FutureArmType.SHORT_ROTATION),
                    "Neutral bias → range-fade both rotation arms (compression gate decides wide vs tight)");
        };
    }

    private ArmSelection selectLong(OpenZone openZone) {
        return switch (openZone) {
            case RANGE -> ArmSelection.primary(FutureArmType.LONG_ROTATION,
                    List.of(FutureArmType.LONG_BREAKOUT, FutureArmType.SHORT_ROTATION,
                            FutureArmType.SHORT_BREAKDOWN));
            case BREAKOUT -> ArmSelection.primary(FutureArmType.LONG_BREAKOUT,
                    List.of(FutureArmType.LONG_ROTATION, FutureArmType.SHORT_ROTATION,
                            FutureArmType.SHORT_BREAKDOWN));
            case BREAKDOWN -> ArmSelection.noTrade(
                    "Bullish bias but open broke down (below L3) — no long-breakdown arm; stand aside");
        };
    }

    private ArmSelection selectShort(OpenZone openZone) {
        return switch (openZone) {
            case RANGE -> ArmSelection.primary(FutureArmType.SHORT_ROTATION,
                    List.of(FutureArmType.SHORT_BREAKDOWN, FutureArmType.LONG_ROTATION,
                            FutureArmType.LONG_BREAKOUT));
            case BREAKDOWN -> ArmSelection.primary(FutureArmType.SHORT_BREAKDOWN,
                    List.of(FutureArmType.SHORT_ROTATION, FutureArmType.LONG_ROTATION,
                            FutureArmType.LONG_BREAKOUT));
            case BREAKOUT -> ArmSelection.noTrade(
                    "Bearish bias but open broke out (above H3) — no short-breakout arm; stand aside");
        };
    }
}
