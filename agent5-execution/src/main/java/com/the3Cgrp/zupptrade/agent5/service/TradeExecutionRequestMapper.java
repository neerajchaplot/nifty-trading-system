package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.dto.ExecuteTradeRequest;
import com.the3Cgrp.zupptrade.agent5.dto.LegOrderRequest;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;

import java.util.List;

/**
 * Converts Agent 2's TradeCardDto → Agent 5's ExecuteTradeRequest.
 *
 * Called by the Orchestrator immediately after Agent 2 POST /confirm returns CONFIRMED.
 * The Orchestrator passes the converted request to Agent 5 POST /execute.
 *
 * Limit price = ltp at recommendation time. Agent 5's 30-second fill timeout handles
 * cases where the market has moved; it converts to MARKET if the LIMIT does not fill.
 *
 * Quantity per leg = lots × lotSize (total contracts, not lot count).
 */
public final class TradeExecutionRequestMapper {

    private TradeExecutionRequestMapper() {}

    public static ExecuteTradeRequest from(TradeCardDto tradeCard) {
        int totalQty = tradeCard.lots() * tradeCard.lotSize();
        boolean isIronCondor = tradeCard.shortLeg2() != null;
        List<LegOrderRequest> legs = isIronCondor
                ? List.of(legRequest(tradeCard.shortLeg(), totalQty),
                          legRequest(tradeCard.longLeg(),  totalQty),
                          legRequest(tradeCard.shortLeg2(), totalQty),
                          legRequest(tradeCard.longLeg2(),  totalQty))
                : List.of(legRequest(tradeCard.shortLeg(), totalQty),
                          legRequest(tradeCard.longLeg(),  totalQty));
        return new ExecuteTradeRequest(tradeCard.tradeId(), legs);
    }

    private static LegOrderRequest legRequest(TradeLegDto leg, int qty) {
        return new LegOrderRequest(
                leg.instrumentKey(),
                leg.optionType(),
                leg.strike(),
                leg.action(),
                leg.ltp(),
                qty
        );
    }
}
