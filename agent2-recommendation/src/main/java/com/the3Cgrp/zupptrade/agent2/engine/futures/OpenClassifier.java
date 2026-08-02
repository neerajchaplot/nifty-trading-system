package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.CamarillaLevels;
import com.the3Cgrp.zupptrade.shared.enums.OpenZone;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Classifies the open relative to the Camarilla range band (spec §2.2).
 *
 * openPx = prevClose + giftPts.
 *   openPx >= H3  → BREAKOUT   (stretched to / above the upper band)
 *   openPx <= L3  → BREAKDOWN  (stretched to / below the lower band)
 *   L3 < openPx < H3 → RANGE   (mid-range)
 *
 * The L3–H3 band is the deterministic definition of "mid-range"; below it heads
 * toward L4 (breakdown), above it toward H4 (breakout).
 */
@Component
public class OpenClassifier {

    public OpenZone classify(BigDecimal openPx, CamarillaLevels levels) {
        if (openPx.compareTo(levels.h3()) >= 0) {
            return OpenZone.BREAKOUT;
        }
        if (openPx.compareTo(levels.l3()) <= 0) {
            return OpenZone.BREAKDOWN;
        }
        return OpenZone.RANGE;
    }
}
