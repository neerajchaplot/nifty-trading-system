package com.the3Cgrp.zupptrade.agent2.engine.layer1;

import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;

/**
 * Result of the Layer 1 decision matrix.
 *
 * @param strategy        the selected strategy (always a real, tradeable strategy except the
 *                        hard NO_TRADE case for VIX EXTREME / LOW-confidence directional credit)
 * @param spreadDirection CREDIT or DEBIT (null for NO_TRADE/SKIP)
 * @param softSkip        true when the strategy is a low-conviction / thin-premium fallback the
 *                        matrix would rather not take, but still produces so the RoC/PoP/loss gates
 *                        and the user (not Layer 1) make the final go/no-go call. A soft skip does
 *                        NOT force REJECTED — the trade card is built and the gates decide.
 * @param softReason      audit reason for the soft skip (null when softSkip is false)
 */
public record StrategySelection(Strategy strategy, SpreadDirection spreadDirection,
                                boolean softSkip, String softReason) {

    /** Convenience for the common, full-conviction selection (no soft skip). */
    public StrategySelection(Strategy strategy, SpreadDirection spreadDirection) {
        this(strategy, spreadDirection, false, null);
    }

    /** A low-conviction fallback that still trades — gates + user decide. */
    static StrategySelection soft(Strategy strategy, SpreadDirection spreadDirection, String reason) {
        return new StrategySelection(strategy, spreadDirection, true, reason);
    }
}
