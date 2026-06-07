package com.the3Cgrp.zupptrade.agent5.dto;

import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;

import java.math.BigDecimal;

/**
 * Actual fill result for one leg — stored in trades.entry_fills JSONB.
 *
 * orderId:        Upstox order_id — use to query status or cancel.
 * correlationId:  The correlation_id we set (ZUPP_{id8}_L{n}) — maps order back to our leg.
 * averageFillPrice: VWAP across all partial fills (from Upstox average_price field).
 * slippagePerUnit:  SELL: limitPrice − avgFill (positive = we got less than expected).
 *                   BUY:  avgFill − limitPrice (positive = we paid more than expected).
 *                   Both positive = unfavourable. Used for slippage alert only.
 */
public record LegFillDto(
        String orderId,
        String correlationId,
        String instrumentKey,
        OptionType optionType,
        int strike,
        LegAction action,
        int quantityFilled,
        BigDecimal limitPrice,
        BigDecimal averageFillPrice,
        BigDecimal slippagePerUnit
) {}
