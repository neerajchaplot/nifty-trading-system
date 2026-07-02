package com.the3Cgrp.zupptrade.agent3.client;

import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Agent 3 → Agent 5 REST client for new trade execution.
 *
 * Distinct from Agent5ExitClient: this calls POST /api/v1/agent5/execute
 * (new trade entry) while Agent5ExitClient calls POST /api/v1/agent5/exit/{tradeId}
 * (closing an existing position).
 *
 * Used only by ReadjustmentService to execute the re-entry trade after
 * Agent 2 confirms the new recommendation.
 *
 * Returns true on HTTP 200 with executionStatus=ACTIVE, false on any failure.
 * Both legs are placed simultaneously via Agent 5's multi/place endpoint.
 */
@Service
public class Agent5ExecuteClient {

    private static final Logger log = LoggerFactory.getLogger(Agent5ExecuteClient.class);

    private final RestClient agent5RestClient;

    public Agent5ExecuteClient(@Qualifier("agent5RestClient") RestClient agent5RestClient) {
        this.agent5RestClient = agent5RestClient;
    }

    /**
     * Submits the confirmed trade card to Agent 5 for execution.
     *
     * leg ordering: short leg (index 0) first, long leg (index 1) second —
     * consistent with Agent 5's ExecuteTradeRequest convention.
     *
     * @param card confirmed trade card from Agent 2 /confirm
     * @return true if both legs filled and trade is ACTIVE
     */
    public boolean execute(TradeCardDto card) {
        int quantity = card.lots() * card.lotSize();
        boolean isIronCondor = card.shortLeg2() != null;

        List<LegExecRequest> legRequests;
        if (isIronCondor) {
            legRequests = List.of(
                    new LegExecRequest(card.shortLeg().instrumentKey(), card.shortLeg().optionType(),
                            card.shortLeg().strike(), card.shortLeg().action(), card.shortLeg().ltp(), quantity),
                    new LegExecRequest(card.longLeg().instrumentKey(), card.longLeg().optionType(),
                            card.longLeg().strike(), card.longLeg().action(), card.longLeg().ltp(), quantity),
                    new LegExecRequest(card.shortLeg2().instrumentKey(), card.shortLeg2().optionType(),
                            card.shortLeg2().strike(), card.shortLeg2().action(), card.shortLeg2().ltp(), quantity),
                    new LegExecRequest(card.longLeg2().instrumentKey(), card.longLeg2().optionType(),
                            card.longLeg2().strike(), card.longLeg2().action(), card.longLeg2().ltp(), quantity));
        } else {
            legRequests = List.of(
                    new LegExecRequest(card.shortLeg().instrumentKey(), card.shortLeg().optionType(),
                            card.shortLeg().strike(), card.shortLeg().action(), card.shortLeg().ltp(), quantity),
                    new LegExecRequest(card.longLeg().instrumentKey(), card.longLeg().optionType(),
                            card.longLeg().strike(), card.longLeg().action(), card.longLeg().ltp(), quantity));
        }

        ExecuteRequest request = new ExecuteRequest(card.tradeId(), legRequests);

        try {
            ExecuteResponse response = agent5RestClient.post()
                    .uri("/api/v1/agent5/execute")
                    .body(request)
                    .retrieve()
                    .body(ExecuteResponse.class);

            if (response == null) {
                log.error("agent5.execute.null_response tradeId={}", card.tradeId());
                return false;
            }

            boolean success = TradeStatus.ACTIVE.name().equals(response.executionStatus());
            if (success && response.slippageAlert()) {
                log.warn("agent5.execute.slippage_alert tradeId={} rejectionReason={}",
                        card.tradeId(), response.rejectionReason());
            }
            log.info("agent5.execute.response tradeId={} executionStatus={} success={}",
                    card.tradeId(), response.executionStatus(), success);
            return success;

        } catch (HttpStatusCodeException e) {
            log.error("agent5.execute.http_error tradeId={} httpStatus={} body={}",
                    card.tradeId(), e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("agent5.execute.error tradeId={} error={}", card.tradeId(), e.getMessage());
            return false;
        }
    }

    /** Local projection — matches ExecuteTradeRequest JSON shape in agent5-execution. */
    private record ExecuteRequest(UUID tradeId, List<LegExecRequest> legs) {}

    /** Local projection — matches LegOrderRequest JSON shape in agent5-execution. */
    private record LegExecRequest(
            String instrumentKey,
            OptionType optionType,
            int strike,
            LegAction action,
            BigDecimal limitPrice,
            int quantity) {}

    /** Local projection — minimal fields from ExecuteTradeResponse. */
    private record ExecuteResponse(
            String tradeId,
            String executionStatus,
            boolean slippageAlert,
            String rejectionReason) {}
}
