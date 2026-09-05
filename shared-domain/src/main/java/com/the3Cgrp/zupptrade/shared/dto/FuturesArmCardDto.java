package com.the3Cgrp.zupptrade.shared.dto;

import com.the3Cgrp.zupptrade.shared.enums.ArmCardStatus;
import com.the3Cgrp.zupptrade.shared.enums.ArmReachability;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.TradeDirection;

import java.math.BigDecimal;

/**
 * One selectable trade on the futures card. Carries both the machine arm type and a
 * plain-English {@code label} for the UI (e.g. "Buy the dip"). R:R and cost are shown
 * for the user's judgement — they never block (confidence is the only hard gate).
 */
public record FuturesArmCardDto(
        FutureArmType armType,
        String label,
        TradeDirection direction,
        ArmCardStatus status,
        String blockedReason,
        // Levels
        BigDecimal entry,
        BigDecimal stop,
        BigDecimal target,
        // Risk / reward (display-only)
        BigDecimal riskPoints,
        BigDecimal rewardPoints,
        BigDecimal rrGross,
        BigDecimal rrAfterCost,
        BigDecimal costPoints,
        // Ranking
        BigDecimal probabilityPct,
        // Sizing + margin (this arm's own Camarilla stop distance)
        int lots,
        int lotSize,
        BigDecimal riskPerLot,
        BigDecimal riskTotal,
        BigDecimal marginEstimate,
        BigDecimal notional,
        // Live, on-read overlay: is this arm's entry still catchable at the current level? Nullable
        // (null = level unavailable / not evaluated). Never persisted — always recomputed on read.
        ArmReachability reachability
) {
    /** Returns a copy with the reachability overlay set (all other fields unchanged). */
    public FuturesArmCardDto withReachability(ArmReachability r) {
        return new FuturesArmCardDto(armType, label, direction, status, blockedReason,
                entry, stop, target, riskPoints, rewardPoints, rrGross, rrAfterCost, costPoints,
                probabilityPct, lots, lotSize, riskPerLot, riskTotal, marginEstimate, notional, r);
    }
}
