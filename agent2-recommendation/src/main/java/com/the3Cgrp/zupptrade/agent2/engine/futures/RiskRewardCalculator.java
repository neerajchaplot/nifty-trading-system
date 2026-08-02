package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmRiskReward;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * R:R per arm (spec §5):
 * <pre>
 * risk   = |entry - stop|
 * reward = |target - entry|
 * R:R    = reward / risk   (1 decimal, published)
 * </pre>
 * Cost-aware R:R subtracts the round-trip cost (points) from reward before dividing —
 * this {@code rrAfterCost} is what the two-gate validator checks. Re-run at the actual
 * fill price before firing (spec §5): confirmation costs points and widens risk.
 */
@Component
public class RiskRewardCalculator {

    private static final int RR_SCALE = 1;
    private static final int PTS_SCALE = 2;

    public ArmRiskReward compute(BigDecimal entry, BigDecimal stop, BigDecimal target,
                                 BigDecimal roundTripCostPoints) {
        BigDecimal risk = entry.subtract(stop).abs().setScale(PTS_SCALE, RoundingMode.HALF_UP);
        BigDecimal reward = target.subtract(entry).abs().setScale(PTS_SCALE, RoundingMode.HALF_UP);
        BigDecimal cost = roundTripCostPoints == null ? BigDecimal.ZERO
                : roundTripCostPoints.setScale(PTS_SCALE, RoundingMode.HALF_UP);

        BigDecimal rrGross = ratio(reward, risk);
        BigDecimal netReward = reward.subtract(cost).max(BigDecimal.ZERO);
        BigDecimal rrAfterCost = ratio(netReward, risk);

        return new ArmRiskReward(risk, reward, cost, rrGross, rrAfterCost);
    }

    private BigDecimal ratio(BigDecimal reward, BigDecimal risk) {
        if (risk.signum() <= 0) {
            return BigDecimal.ZERO.setScale(RR_SCALE);
        }
        return reward.divide(risk, RR_SCALE, RoundingMode.HALF_UP);
    }
}
