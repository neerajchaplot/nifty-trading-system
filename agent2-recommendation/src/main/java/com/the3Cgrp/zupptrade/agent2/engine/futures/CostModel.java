package com.the3Cgrp.zupptrade.agent2.engine.futures;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Round-trip cost model for the R:R gate (spec §6.2): subtract slippage + brokerage
 * + taxes from reward before the 1.5 test. Expressed in index POINTS so it plugs
 * straight into {@link RiskRewardCalculator}.
 *
 * cost(points) = slippagePoints                      (entry+exit slippage, config)
 *              + (flatChargesPerRoundTrip / pointValuePerLot)   (brokerage+GST, ₹→points)
 *              + (entry × taxPctPerRoundTrip)         (STT/exchange turnover taxes)
 *
 * All three components are config-driven and calibratable — the spec says test each
 * arm's net P&L separately, since thin rotation fades often die here.
 */
@Component
public class CostModel {

    private static final int SCALE = 2;

    /**
     * @param entry              arm entry price (for turnover-based taxes)
     * @param pointValuePerLot   ₹ per index point per lot (= lot size)
     * @param slippagePoints     assumed slippage points per round trip
     * @param flatChargesPerRt   flat brokerage + GST in ₹ per round trip (both legs)
     * @param taxPctPerRt        turnover tax fraction per round trip (e.g. 0.0004)
     */
    public BigDecimal roundTripCostPoints(BigDecimal entry, BigDecimal pointValuePerLot,
                                          BigDecimal slippagePoints, BigDecimal flatChargesPerRt,
                                          BigDecimal taxPctPerRt) {
        BigDecimal charges = pointValuePerLot.signum() > 0
                ? flatChargesPerRt.divide(pointValuePerLot, SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal taxes = entry.multiply(taxPctPerRt).setScale(SCALE, RoundingMode.HALF_UP);
        return slippagePoints.add(charges).add(taxes).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
