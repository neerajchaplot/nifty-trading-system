package com.the3Cgrp.zupptrade.agent3.client;

import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.RecommendRequestDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeConfirmRequestDto;
import com.the3Cgrp.zupptrade.shared.enums.ConfirmAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 3 → Agent 2 REST client for automated readjustment re-entry.
 *
 * Wraps two Agent 2 endpoints:
 *   POST /api/v1/agent2/recommend — get a new trade recommendation
 *   POST /api/v1/agent2/confirm  — auto-confirm without user interaction
 *
 * Both return Optional.empty() on any failure — ReadjustmentService handles
 * absent results by alerting rather than throwing.
 *
 * Gate 1 relaxation is injected via RecommendRequestDto.relaxedGate1PopPct:
 *   65% when VIX ≤ 22 (normal stress)
 *   70% when VIX > 22 (elevated stress)
 * Standard threshold (80%) is used for normal user-initiated recommendations.
 */
@Service
public class Agent2RecommendClient {

    private static final Logger log = LoggerFactory.getLogger(Agent2RecommendClient.class);

    private final RestClient agent2RestClient;

    public Agent2RecommendClient(@Qualifier("agent2RestClient") RestClient agent2RestClient) {
        this.agent2RestClient = agent2RestClient;
    }

    /**
     * Calls Agent 2 /recommend with a relaxed G1 PoP threshold for readjustment.
     *
     * @param userProfileId   user risk profile (capital, constraints)
     * @param signalId        fresh Agent 1 signal ID from Agent1ScoreClient
     * @param relaxedPopPct   relaxed G1 gate threshold (percentage 0-100, e.g. 65.0)
     * @return new trade card in PENDING_CONFIRM state, or empty if gates failed or error
     */
    public Optional<TradeCardDto> recommend(UUID userProfileId, UUID signalId, BigDecimal relaxedPopPct) {
        RecommendRequestDto request = new RecommendRequestDto(userProfileId, signalId, relaxedPopPct);

        try {
            TradeCardDto card = agent2RestClient.post()
                    .uri("/api/v1/agent2/recommend")
                    .body(request)
                    .retrieve()
                    .body(TradeCardDto.class);

            if (card == null || card.tradeId() == null) {
                log.error("agent2.recommend.null_response signalId={}", signalId);
                return Optional.empty();
            }

            log.info("agent2.recommend.success tradeId={} strategy={} pop={} relaxedPopPct={}",
                    card.tradeId(), card.strategy(), card.pop(), relaxedPopPct);
            return Optional.of(card);

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                // 422 = gates failed — not an error, just no valid trade available
                log.warn("agent2.recommend.gates_failed signalId={} relaxedPopPct={} body={}",
                        signalId, relaxedPopPct, e.getResponseBodyAsString());
            } else {
                log.error("agent2.recommend.http_error signalId={} httpStatus={} body={}",
                        signalId, e.getStatusCode(), e.getResponseBodyAsString());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("agent2.recommend.error signalId={} error={}", signalId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Calls Agent 2 /monitor-config to compute and persist the monitor config for a trade.
     *
     * Agent 2 reads the trade from DB, computes T1/T2/T3 thresholds and greeks using the
     * actual fill prices, writes monitor_config to the trades table, and returns the DTO.
     *
     * Called by MonitorSchedulerService when a trade is ACTIVE but monitor_config is null
     * — this happens when Agent 5 set the trade ACTIVE but the /monitor-config call was
     * never triggered (or failed).
     *
     * @param tradeId        the ACTIVE trade whose monitor_config needs populating
     * @param shortFillPrice actual fill price of the short (SELL) leg
     * @param longFillPrice  actual fill price of the long (BUY) leg
     * @return populated MonitorConfigDto, or empty if Agent 2 is unavailable or errors
     */
    public Optional<MonitorConfigDto> fetchMonitorConfig(UUID tradeId,
                                                         BigDecimal shortFillPrice,
                                                         BigDecimal longFillPrice) {
        try {
            MonitorConfigDto dto = agent2RestClient.get()
                    .uri("/api/v1/agent2/monitor-config/{id}", tradeId)
                    .header("X-Short-Fill-Price", shortFillPrice.toPlainString())
                    .header("X-Long-Fill-Price",  longFillPrice.toPlainString())
                    .retrieve()
                    .body(MonitorConfigDto.class);

            if (dto == null) {
                log.error("agent2.monitor_config.null_response tradeId={}", tradeId);
                return Optional.empty();
            }

            log.info("agent2.monitor_config.success tradeId={}", tradeId);
            return Optional.of(dto);

        } catch (HttpStatusCodeException e) {
            log.error("agent2.monitor_config.http_error tradeId={} httpStatus={} body={}",
                    tradeId, e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            log.error("agent2.monitor_config.error tradeId={} error={}", tradeId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Auto-confirms a PENDING_CONFIRM trade card without user interaction.
     * Used only in the automated readjustment flow.
     *
     * @param tradeId the trade to confirm
     * @return confirmed trade card, or empty on failure
     */
    public Optional<TradeCardDto> confirm(UUID tradeId) {
        TradeConfirmRequestDto request = new TradeConfirmRequestDto(tradeId, ConfirmAction.CONFIRM, null);

        try {
            TradeCardDto confirmed = agent2RestClient.post()
                    .uri("/api/v1/agent2/confirm")
                    .body(request)
                    .retrieve()
                    .body(TradeCardDto.class);

            if (confirmed == null || confirmed.tradeId() == null) {
                log.error("agent2.confirm.null_response tradeId={}", tradeId);
                return Optional.empty();
            }

            log.info("agent2.confirm.success tradeId={} status={}", confirmed.tradeId(), confirmed.status());
            return Optional.of(confirmed);

        } catch (HttpStatusCodeException e) {
            log.error("agent2.confirm.http_error tradeId={} httpStatus={} body={}",
                    tradeId, e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            log.error("agent2.confirm.error tradeId={} error={}", tradeId, e.getMessage());
            return Optional.empty();
        }
    }
}
