package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.MarginEstimate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Recommend-time futures margin estimate (spec §6.8):
 * <pre>
 * notional = entry × lotSize × lots
 * margin   = notional × marginPct        (config-driven, ~ SPAN+exposure %)
 * </pre>
 * Fast and decoupled so the user sees a number at approval time; the authoritative
 * Upstox /v2/charges/margin check stays in Agent 5 immediately before the GTT fires.
 */
@Component
public class MarginEstimator {

    private static final int SCALE = 2;

    public MarginEstimate estimate(BigDecimal entry, int lotSize, int lots, BigDecimal marginPct) {
        BigDecimal notional = entry
                .multiply(BigDecimal.valueOf(lotSize))
                .multiply(BigDecimal.valueOf(lots))
                .setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal margin = notional.multiply(marginPct).setScale(SCALE, RoundingMode.HALF_UP);
        return new MarginEstimate(notional, marginPct, margin);
    }
}
