package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.shared.enums.ArmReachability;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Live, on-read overlay judging whether an arm's entry is still catchable at the current Nifty level.
 *
 * Direction-agnostic rule: every arm defines a price window between its stop and its target. While
 * the live level sits inside that window the setup is still in play (REACHABLE); once it leaves the
 * window the opportunity is gone (MISSED) — either the move already ran past the target, or price
 * broke past the stop and killed the premise. Pure and side-effect-free (no Spring/DB/I-O), so it is
 * unit-testable in isolation.
 */
@Component
public class ArmReachabilityCalculator {

    /**
     * @param currentLevel live Nifty index level; null when the live level is unavailable
     * @return REACHABLE / MISSED, or null when {@code currentLevel} is null (undetermined)
     */
    public ArmReachability evaluate(BigDecimal currentLevel, BigDecimal stop, BigDecimal target) {
        if (currentLevel == null || stop == null || target == null) {
            return null;
        }
        BigDecimal lower = stop.min(target);
        BigDecimal upper = stop.max(target);
        boolean inside = currentLevel.compareTo(lower) >= 0 && currentLevel.compareTo(upper) <= 0;
        return inside ? ArmReachability.REACHABLE : ArmReachability.MISSED;
    }
}
