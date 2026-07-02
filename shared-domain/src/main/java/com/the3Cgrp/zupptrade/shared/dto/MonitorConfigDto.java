package com.the3Cgrp.zupptrade.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Monitoring configuration built by Agent 2 after fills are confirmed by Agent 5.
 *
 * Two-leg spreads: shortLeg + longLeg only; shortLeg2/longLeg2 are null (omitted from JSON).
 * Iron Condor: all four legs present.
 *   shortLeg  = PE SELL (put spread short)    longLeg  = PE BUY (put spread long)
 *   shortLeg2 = CE SELL (call spread short)   longLeg2 = CE BUY (call spread long)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonitorConfigDto(
        UUID tradeId,
        Strategy strategy,
        SpreadDirection spreadDirection,
        TradeLegDto shortLeg,              // PE SELL for IC; SELL leg for 2-leg spreads
        TradeLegDto longLeg,               // PE BUY  for IC; BUY  leg for 2-leg spreads
        TradeLegDto shortLeg2,             // IC only: CE SELL
        TradeLegDto longLeg2,              // IC only: CE BUY
        BigDecimal actualNetPremiumPerUnit,
        int lots,
        int lotSize,
        BigDecimal maxProfitTotal,
        BigDecimal actualMaxLossTotal,
        boolean slippageAlert,
        BigDecimal slippageAmount,
        MonitorThresholdsDto thresholds,
        LocalDate expiryDate,
        int dte
) {

    /** Factory for 2-leg spreads — shortLeg2 and longLeg2 are null. */
    public static MonitorConfigDto twoLeg(UUID tradeId, Strategy strategy, SpreadDirection dir,
                                           TradeLegDto shortLeg, TradeLegDto longLeg,
                                           BigDecimal netPremium, int lots, int lotSize,
                                           BigDecimal maxProfit, BigDecimal maxLoss,
                                           boolean slippageAlert, BigDecimal slippageAmount,
                                           MonitorThresholdsDto thresholds,
                                           LocalDate expiryDate, int dte) {
        return new MonitorConfigDto(tradeId, strategy, dir,
                shortLeg, longLeg, null, null,
                netPremium, lots, lotSize, maxProfit, maxLoss,
                slippageAlert, slippageAmount, thresholds, expiryDate, dte);
    }

    /** Factory for Iron Condor / Wide Iron Condor — all four legs. */
    public static MonitorConfigDto ironCondor(UUID tradeId, Strategy strategy, SpreadDirection dir,
                                               TradeLegDto peShort, TradeLegDto peLong,
                                               TradeLegDto ceShort, TradeLegDto ceLong,
                                               BigDecimal netPremium, int lots, int lotSize,
                                               BigDecimal maxProfit, BigDecimal maxLoss,
                                               boolean slippageAlert, BigDecimal slippageAmount,
                                               MonitorThresholdsDto thresholds,
                                               LocalDate expiryDate, int dte) {
        return new MonitorConfigDto(tradeId, strategy, dir,
                peShort, peLong, ceShort, ceLong,
                netPremium, lots, lotSize, maxProfit, maxLoss,
                slippageAlert, slippageAmount, thresholds, expiryDate, dte);
    }
}
