package com.the3Cgrp.zupptrade.agent3.client;

import com.the3Cgrp.zupptrade.shared.dto.ExitTradeRequest;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Agent 3 → Agent 5 REST client for trade exit.
 *
 * Called by MonitorSchedulerService when an EXIT action is required.
 * Thin wrapper around Agent 5's POST /api/v1/agent5/exit/{tradeId} endpoint.
 *
 * Returns true on HTTP 200 with CLOSED status, false on any error.
 * All failures are logged — the caller writes to the notifications table.
 */
@Service
public class Agent5ExitClient {

    private static final Logger log = LoggerFactory.getLogger(Agent5ExitClient.class);

    private final RestClient agent5RestClient;

    public Agent5ExitClient(@Qualifier("agent5RestClient") RestClient agent5RestClient) {
        this.agent5RestClient = agent5RestClient;
    }

    /**
     * Places a market exit order for a trade's legs via Agent 5.
     *
     * @param tradeId        the trade to exit
     * @param reason         exit reason code (T3_EXIT_BREACH, VIX_SPIKE, etc.)
     * @param shortKey       Upstox instrument key for the short leg
     * @param shortAction    original action of short leg (SELL for credit spread short strike)
     * @param longKey        Upstox instrument key for the long leg
     * @param longAction     original action of long leg (BUY for credit spread long strike)
     * @param quantity       number of units per leg (lots × lotSize)
     * @return true if Agent 5 successfully placed the exit orders
     */
    public boolean exitTrade(UUID tradeId, String reason,
                              String shortKey, LegAction shortAction,
                              String longKey, LegAction longAction,
                              int quantity) {
        ExitTradeRequest request = new ExitTradeRequest(
                tradeId,
                reason,
                List.of(
                        new ExitTradeRequest.ExitLeg(shortKey, shortAction, quantity),
                        new ExitTradeRequest.ExitLeg(longKey, longAction, quantity)
                )
        );

        try {
            ExitResponse response = agent5RestClient.post()
                    .uri("/api/v1/agent5/exit/{tradeId}", tradeId)
                    .body(request)
                    .retrieve()
                    .body(ExitResponse.class);

            if (response == null) {
                log.error("agent5.exit.null_response tradeId={}", tradeId);
                return false;
            }

            boolean success = TradeStatus.CLOSED.name().equals(response.status());
            log.info("agent5.exit.response tradeId={} status={} success={}", tradeId, response.status(), success);
            return success;

        } catch (HttpStatusCodeException e) {
            log.error("agent5.exit.http_error tradeId={} httpStatus={} body={}",
                    tradeId, e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("agent5.exit.error tradeId={} error={}", tradeId, e.getMessage());
            return false;
        }
    }

    /** Minimal projection of Agent 5's ExitTradeResponse to avoid cross-module DTO dependency. */
    private record ExitResponse(String tradeId, String status, String failureReason) {}
}
