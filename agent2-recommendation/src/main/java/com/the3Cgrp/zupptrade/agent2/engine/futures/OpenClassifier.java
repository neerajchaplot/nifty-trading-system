package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.CamarillaLevels;
import com.the3Cgrp.zupptrade.shared.enums.OpenZone;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Classifies the session open relative to the Camarilla range band (spec §2.2).
 *
 * {@code sessionOpen} is the fixed opening reference (GIFT-implied open pre-market, else today's
 * actual opening print) — NOT the drifting live spot. It is resolved once by SessionOpenResolver.
 *   sessionOpen >= H3  → BREAKOUT   (stretched to / above the upper band)
 *   sessionOpen <= L3  → BREAKDOWN  (stretched to / below the lower band)
 *   L3 < sessionOpen < H3 → RANGE   (mid-range)
 *
 * The L3–H3 band is the deterministic definition of "mid-range"; below it heads
 * toward L4 (breakdown), above it toward H4 (breakout).
 */
@Component
public class OpenClassifier {

    public OpenZone classify(BigDecimal sessionOpen, CamarillaLevels levels) {
        if (sessionOpen.compareTo(levels.h3()) >= 0) {
            return OpenZone.BREAKOUT;
        }
        if (sessionOpen.compareTo(levels.l3()) <= 0) {
            return OpenZone.BREAKDOWN;
        }
        return OpenZone.RANGE;
    }
}
