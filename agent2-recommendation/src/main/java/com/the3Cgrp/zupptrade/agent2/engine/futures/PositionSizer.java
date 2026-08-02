package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.SizingResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fixed-fractional position sizing (spec §6.5):
 * <pre>
 * riskPerLot = stopPoints × pointValuePerLot        (pointValuePerLot = lot size, ₹1/pt/unit)
 * lots       = floor(riskCapital / riskPerLot)
 * </pre>
 * Stop distance is Camarilla-based (|entry − stop| of the selected arm) — no ATR.
 * riskCapital is a fixed % of capital, computed by the caller.
 */
// Explicit bean name avoids a clash with layer5.PositionSizer (both default to "positionSizer").
// Injection is by type, so no other reference changes are needed.
@Component("futuresPositionSizer")
public class PositionSizer {

    private static final int SCALE = 2;

    /**
     * @param riskCapital        rupees the user is willing to lose on this trade
     * @param stopPoints         |entry − stop| in index points (Camarilla-based)
     * @param lotSize            contract lot size (65 for Nifty) — from reference_data, never hardcoded
     */
    public SizingResult size(BigDecimal riskCapital, BigDecimal stopPoints, int lotSize) {
        BigDecimal pointValuePerLot = BigDecimal.valueOf(lotSize); // ₹1/point/unit × lotSize
        BigDecimal riskPerLot = stopPoints.multiply(pointValuePerLot).setScale(SCALE, RoundingMode.HALF_UP);

        int lots = 0;
        if (riskPerLot.signum() > 0) {
            lots = riskCapital.divide(riskPerLot, 0, RoundingMode.FLOOR).intValue();
        }
        BigDecimal riskTotal = riskPerLot.multiply(BigDecimal.valueOf(lots)).setScale(SCALE, RoundingMode.HALF_UP);

        return new SizingResult(lots, lotSize,
                stopPoints.setScale(SCALE, RoundingMode.HALF_UP),
                pointValuePerLot.setScale(SCALE, RoundingMode.HALF_UP),
                riskCapital.setScale(SCALE, RoundingMode.HALF_UP),
                riskPerLot, riskTotal);
    }
}
