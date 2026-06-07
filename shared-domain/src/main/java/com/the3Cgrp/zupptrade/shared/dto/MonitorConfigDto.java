package com.the3Cgrp.zupptrade.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonitorConfigDto(
        UUID tradeId,
        Strategy strategy,
        SpreadDirection spreadDirection,
        TradeLegDto shortLeg,              // with actual fill price from Agent 5
        TradeLegDto longLeg,               // with actual fill price from Agent 5
        BigDecimal actualNetPremiumPerUnit,
        int lots,
        int lotSize,
        BigDecimal maxProfitTotal,
        BigDecimal actualMaxLossTotal,
        boolean slippageAlert,             // true if actual < expected - 10%
        BigDecimal slippageAmount,         // Rs amount of slippage if alert triggered
        MonitorThresholdsDto thresholds,
        LocalDate expiryDate,
        int dte
) {}
