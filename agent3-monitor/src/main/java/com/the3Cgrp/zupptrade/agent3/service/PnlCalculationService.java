package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes mark-to-market P&L for an open spread position.
 *
 * Sign convention:
 *   Positive P&L = profit (trade is moving in our favour).
 *   Negative P&L = loss  (trade is moving against us).
 *
 * Credit spread (SELL): we received premium upfront.
 *   currentCloseCost = shortLegLtp - longLegLtp
 *   P&L = (actualNetPremiumReceived - currentCloseCost) × lots × lotSize
 *
 * Debit spread (BUY): we paid premium upfront.
 *   currentValue = longLegLtp - shortLegLtp
 *   P&L = (currentValue - actualNetPremiumPaid) × lots × lotSize
 */
@Service
public class PnlCalculationService {

    /**
     * @return mark-to-market P&L in Rs. Positive = profit, negative = loss.
     */
    public BigDecimal calculateMtmPnl(MonitorConfigDto config,
                                       BigDecimal shortLegLtp,
                                       BigDecimal longLegLtp) {
        BigDecimal currentNetPremium = currentNetPremium(config.spreadDirection(), shortLegLtp, longLegLtp);
        BigDecimal positionSize = BigDecimal.valueOf((long) config.lots() * config.lotSize());

        if (config.spreadDirection() == SpreadDirection.CREDIT) {
            return config.actualNetPremiumPerUnit()
                         .subtract(currentNetPremium)
                         .multiply(positionSize)
                         .setScale(2, RoundingMode.HALF_UP);
        } else {
            return currentNetPremium
                         .subtract(config.actualNetPremiumPerUnit())
                         .multiply(positionSize)
                         .setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * Current net premium of the spread (cost to close for credit, value to capture for debit).
     */
    public BigDecimal currentNetPremium(SpreadDirection direction,
                                         BigDecimal shortLegLtp,
                                         BigDecimal longLegLtp) {
        return switch (direction) {
            case CREDIT -> shortLegLtp.subtract(longLegLtp);
            case DEBIT  -> longLegLtp.subtract(shortLegLtp);
        };
    }

    /**
     * Whether the current MTM loss has breached a given threshold.
     * threshold is a positive Rs amount (e.g. 67138.00).
     */
    public boolean hasBreachedLossThreshold(BigDecimal mtmPnl, BigDecimal threshold) {
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0) return false;
        // Loss = negative P&L. breach when abs(loss) >= threshold
        return mtmPnl.compareTo(threshold.negate()) <= 0;
    }
}
