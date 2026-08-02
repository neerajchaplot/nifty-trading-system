package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmProbability;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.ArmSelection;
import com.the3Cgrp.zupptrade.shared.enums.Bias;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Relative probability ranking across the four arms (spec §2.5):
 * <pre>
 * rawProb = biasAlignment + classificationFit + confidenceBoost
 *   biasAlignment    : +2 aligned with Agent 1 bias | -1 opposes | 0 neutral bias
 *   classificationFit: +2 primary | +1 secondary (published alternative) | 0 tail
 *   confidenceBoost  : + (confidence × 2), aligned arms only
 * normalize to 100%, floor each arm at 8%, re-normalize
 * </pre>
 * Published as a ranking, not literal odds, until Agent 4 calibrates.
 */
@Component
public class ProbabilityRanker {

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal FLOOR_PCT = new BigDecimal("8");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCALE = 1;

    private static final List<FutureArmType> ALL = List.of(
            FutureArmType.LONG_ROTATION, FutureArmType.SHORT_ROTATION,
            FutureArmType.LONG_BREAKOUT, FutureArmType.SHORT_BREAKDOWN);

    public List<ArmProbability> rank(ArmSelection selection, Bias bias, BigDecimal confidenceScore) {
        Map<FutureArmType, BigDecimal> raw = new EnumMap<>(FutureArmType.class);
        for (FutureArmType arm : ALL) {
            raw.put(arm, rawScore(arm, selection, bias, confidenceScore));
        }

        // Floor each arm at 8% (spec §2.5), then distribute the REMAINING mass in
        // proportion to positive raw score. Reserving the floors up front (8×4 = 32%)
        // guarantees every arm ends ≥ 8% and the four sum to exactly 100% — unlike
        // "floor then rescale", which would push the floored arms back below 8%.
        BigDecimal positiveSum = raw.values().stream()
                .map(v -> v.max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reserved = FLOOR_PCT.multiply(BigDecimal.valueOf(ALL.size())); // 32
        BigDecimal distributable = HUNDRED.subtract(reserved);                    // 68

        List<ArmProbability> result = new ArrayList<>();
        for (FutureArmType arm : ALL) {
            BigDecimal share = positiveSum.signum() > 0
                    ? raw.get(arm).max(BigDecimal.ZERO)
                        .multiply(distributable).divide(positiveSum, 4, RoundingMode.HALF_UP)
                    : distributable.divide(BigDecimal.valueOf(ALL.size()), 4, RoundingMode.HALF_UP);
            BigDecimal finalPct = FLOOR_PCT.add(share).setScale(SCALE, RoundingMode.HALF_UP);
            result.add(new ArmProbability(arm, raw.get(arm).setScale(SCALE, RoundingMode.HALF_UP), finalPct));
        }
        return result;
    }

    private BigDecimal rawScore(FutureArmType arm, ArmSelection selection,
                                Bias bias, BigDecimal confidenceScore) {
        BigDecimal biasAlignment = biasAlignment(arm, bias);
        BigDecimal classificationFit = classificationFit(arm, selection);
        BigDecimal confidenceBoost = isAligned(arm, bias)
                ? confidenceScore.multiply(TWO)
                : BigDecimal.ZERO;
        return biasAlignment.add(classificationFit).add(confidenceBoost);
    }

    private BigDecimal biasAlignment(FutureArmType arm, Bias bias) {
        if (bias == Bias.NEUTRAL) return BigDecimal.ZERO;
        return isAligned(arm, bias) ? TWO : BigDecimal.valueOf(-1);
    }

    private boolean isAligned(FutureArmType arm, Bias bias) {
        return switch (bias) {
            case BULLISH -> arm.isLong();
            case BEARISH -> !arm.isLong();
            case NEUTRAL -> false;
        };
    }

    private BigDecimal classificationFit(FutureArmType arm, ArmSelection selection) {
        if (arm == selection.primary()) return TWO;
        if (!selection.alternatives().contains(arm)) return BigDecimal.ZERO;
        // Range-fade has no single primary: both fade arms are symmetric secondaries (+1).
        // Directional selections: the top-ranked alternative (index 0) is the secondary
        // path (+1); deeper tails score 0.
        if (selection.rangeFade()) return BigDecimal.ONE;
        return selection.alternatives().indexOf(arm) == 0 ? BigDecimal.ONE : BigDecimal.ZERO;
    }
}
