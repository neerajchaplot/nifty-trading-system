package com.the3Cgrp.zupptrade.agent5.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient.UpstoxOrderException;
import com.the3Cgrp.zupptrade.agent5.client.request.MarginCheckRequest;
import com.the3Cgrp.zupptrade.agent5.client.request.MultiOrderRequest;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MultiOrderResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.agent5.config.Agent5ExecutionProperties;
import com.the3Cgrp.zupptrade.agent5.dto.*;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.TradeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Orchestrates the full trade execution lifecycle.
 *
 * Entry flow:
 *   1. Read expected net premium + expiry from DB (tradeId → trades table)
 *   2. Margin check — all legs together (SPAN spread benefit). Reject if insufficient.
 *   3. multi/place — both legs in ONE HTTP call (simultaneous routing to exchange)
 *      Payload errors → nothing executed → REJECTED
 *   4. Poll both order_ids simultaneously until complete / timeout
 *      Timeout → modifyToMarket (or cancel, per config)
 *   5. Exchange rejection of any leg → cancel unfilled sibling → rollback filled leg
 *   6. Slippage check — alert only, trade stays live
 *   7. Persist fills to trades.entry_fills, set status ACTIVE
 *
 * Exit flow:
 *   multi/place with reversed transaction_type + MARKET order_type
 */
@Service
public class TradeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionService.class);

    private final UpstoxOrderClient      orderClient;
    private final Agent5ExecutionProperties props;
    private final JdbcTemplate           jdbc;
    private final ObjectMapper           mapper;

    public TradeExecutionService(UpstoxOrderClient orderClient,
                                 Agent5ExecutionProperties props,
                                 JdbcTemplate jdbc,
                                 ObjectMapper mapper) {
        this.orderClient = orderClient;
        this.props       = props;
        this.jdbc        = jdbc;
        this.mapper      = mapper;
    }

    // ── Entry ───────────────────────────────────────────────────────────────

    public ExecuteTradeResponse execute(ExecuteTradeRequest request) {
        UUID tradeId = request.tradeId();
        String tag   = OrderTagBuilder.tag(tradeId);

        log.info("execution.start", kv("tradeId", tradeId), kv("tag", tag),
                kv("legCount", request.legs().size()));

        // Read expected net premium from DB — Agent 5 never trusts caller for financial figures
        BigDecimal expectedNet = readExpectedNetPremium(tradeId);
        if (expectedNet == null) {
            return rejected(tradeId, null, "Trade not found or not in CONFIRMED status: " + tradeId);
        }

        // ── Step 1: Margin check ─────────────────────────────────────────────
        MarginCheckResponse margin;
        try {
            margin = orderClient.checkMargin(buildMarginRequest(request));
        } catch (UpstoxOrderException e) {
            log.error("execution.margin.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
            return rejected(tradeId, expectedNet, "Margin check failed: " + e.getMessage());
        }

        if (!margin.hasSufficientMargin()) {
            String reason = String.format("Insufficient margin. Required: ₹%.2f  Available: ₹%.2f",
                    margin.data().finalMargin(), margin.data().availableMargin());
            log.warn("execution.margin.insufficient", kv("tradeId", tradeId),
                    kv("required", margin.data().finalMargin()),
                    kv("available", margin.data().availableMargin()));
            return rejected(tradeId, expectedNet, reason);
        }

        // ── Step 2: multi/place — all legs simultaneously ────────────────────
        MultiOrderResponse placed;
        try {
            placed = orderClient.placeMultiOrder(buildMultiOrderRequest(request, tag));
        } catch (UpstoxOrderException e) {
            log.error("execution.place.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
            return rejected(tradeId, expectedNet, "Order placement failed: " + e.getMessage());
        }

        // ── Step 3: Check payload validation result ──────────────────────────
        if (placed.hasPayloadErrors()) {
            String errors = placed.errors().stream()
                    .map(e -> e.correlationId() + ": " + e.message())
                    .collect(Collectors.joining("; "));
            log.warn("execution.payload.errors", kv("tradeId", tradeId), kv("errors", errors));
            return rejected(tradeId, expectedNet, "Payload error — no orders sent to exchange: " + errors);
        }

        // Build correlationId → orderId map from response
        Map<String, String> correlationToOrderId = new HashMap<>();
        if (placed.data() != null) {
            placed.data().forEach(d -> correlationToOrderId.put(d.correlationId(), d.orderId()));
        }

        // Check for exchange-level errors (partial submission)
        if (!placed.allLegsAccepted()) {
            String errors = placed.errors() != null ? placed.errors().stream()
                    .map(e -> e.correlationId() + ": " + e.message())
                    .collect(Collectors.joining("; ")) : "unknown";
            log.warn("execution.partial.rejection", kv("tradeId", tradeId), kv("errors", errors));
            // Cancel any accepted orders — do not leave a naked position
            correlationToOrderId.values().forEach(orderId -> {
                try { orderClient.cancelOrder(orderId); }
                catch (UpstoxOrderException ex) {
                    log.error("execution.cancel.failed MANUAL INTERVENTION REQUIRED",
                            kv("tradeId", tradeId), kv("orderId", orderId));
                }
            });
            return rejected(tradeId, expectedNet, "Exchange rejected one or more legs: " + errors);
        }

        // ── Step 4: Poll all orders to completion ────────────────────────────
        List<LegFillDto> fills = new ArrayList<>();
        List<String> unfilledOrderIds = new ArrayList<>();

        for (int i = 0; i < request.legs().size(); i++) {
            LegOrderRequest leg      = request.legs().get(i);
            String correlationId     = OrderTagBuilder.correlationId(tradeId, i);
            String orderId           = correlationToOrderId.get(correlationId);

            if (orderId == null) {
                log.error("execution.order.id.missing",
                        kv("tradeId", tradeId), kv("correlationId", correlationId));
                return rejected(tradeId, expectedNet,
                        "No order_id returned for correlation_id=" + correlationId);
            }

            LegFillDto fill = pollToCompletion(orderId, leg, correlationId, tradeId);
            if (fill == null) {
                // This leg rejected/cancelled — rollback everything already filled
                log.warn("execution.leg.failed", kv("tradeId", tradeId), kv("orderId", orderId));
                rollback(fills, tag, tradeId);
                unfilledOrderIds.stream()
                        .filter(id -> !id.equals(orderId))
                        .forEach(id -> {
                            try { orderClient.cancelOrder(id); }
                            catch (UpstoxOrderException ex) {
                                log.error("execution.cancel.unfilled.failed", kv("orderId", id));
                            }
                        });
                return rejected(tradeId, expectedNet,
                        "Leg " + i + " (orderId=" + orderId + ") rejected by exchange. Rollback attempted.");
            }
            fills.add(fill);
            unfilledOrderIds.add(orderId);
        }

        // ── Step 5: Slippage check ───────────────────────────────────────────
        BigDecimal actualNet = computeActualNet(fills);
        boolean slippage     = isSlippage(actualNet, expectedNet);
        String slippageMsg   = null;

        if (slippage) {
            slippageMsg = String.format(
                    "Slippage alert: actual net ₹%.2f vs expected ₹%.2f. Trade is live.",
                    actualNet, expectedNet);
            log.warn("execution.slippage", kv("tradeId", tradeId),
                    kv("actual", actualNet), kv("expected", expectedNet));
        }

        // ── Step 6: Persist fills and set ACTIVE ────────────────────────────
        persistFills(tradeId, fills);

        log.info("execution.complete", kv("tradeId", tradeId),
                kv("actualNet", actualNet), kv("slippageAlert", slippage));

        return new ExecuteTradeResponse(tradeId, TradeStatus.ACTIVE, fills,
                actualNet, expectedNet, slippage, slippageMsg, null, LocalDateTime.now());
    }

    // ── Exit ─────────────────────────────────────────────────────────────────

    public List<LegFillDto> exit(ExitTradeRequest request) {
        UUID tradeId = request.tradeId();
        String exitTag = OrderTagBuilder.exitTag(tradeId);

        log.info("exit.start", kv("tradeId", tradeId),
                kv("reason", request.reason()), kv("legCount", request.exitLegs().size()));

        // Build reverse MARKET multi-order
        List<MultiOrderRequest.OrderLeg> legs = new ArrayList<>();
        for (int i = 0; i < request.exitLegs().size(); i++) {
            ExitTradeRequest.ExitLeg leg = request.exitLegs().get(i);
            String reverse = leg.originalAction() == LegAction.SELL ? "BUY" : "SELL";
            legs.add(MultiOrderRequest.OrderLeg.market(
                    leg.instrumentKey(), reverse, props.getProduct(), leg.quantity(),
                    exitTag, OrderTagBuilder.exitCorrelationId(tradeId, i)));
        }

        MultiOrderResponse placed;
        try {
            placed = orderClient.placeMultiOrder(new MultiOrderRequest(legs));
        } catch (UpstoxOrderException e) {
            log.error("exit.place.failed MANUAL INTERVENTION REQUIRED",
                    kv("tradeId", tradeId), kv("error", e.getMessage()));
            return List.of();
        }

        // Mark trade CLOSED in DB
        jdbc.update("UPDATE trades SET status = 'CLOSED', closed_at = NOW(), " +
                    "close_reason = ? WHERE id = ?", request.reason(), tradeId);

        log.info("exit.complete", kv("tradeId", tradeId),
                kv("success", placed.summary() != null ? placed.summary().success() : 0));

        return List.of(); // exit fills can be fetched from Upstox by tag if needed
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MultiOrderRequest buildMultiOrderRequest(ExecuteTradeRequest request, String tag) {
        List<MultiOrderRequest.OrderLeg> legs = new ArrayList<>();
        for (int i = 0; i < request.legs().size(); i++) {
            LegOrderRequest leg = request.legs().get(i);
            legs.add(MultiOrderRequest.OrderLeg.limit(
                    leg.instrumentKey(), leg.action().name(), props.getProduct(),
                    leg.quantity(), leg.limitPrice(),
                    tag, OrderTagBuilder.correlationId(request.tradeId(), i)));
        }
        return new MultiOrderRequest(legs);
    }

    private MarginCheckRequest buildMarginRequest(ExecuteTradeRequest request) {
        List<MarginCheckRequest.Instrument> instruments = request.legs().stream()
                .map(leg -> new MarginCheckRequest.Instrument(
                        leg.instrumentKey(), leg.action().name(),
                        leg.quantity(), props.getProduct(), leg.limitPrice()))
                .toList();
        return new MarginCheckRequest(instruments);
    }

    private LegFillDto pollToCompletion(String orderId, LegOrderRequest leg,
                                        String correlationId, UUID tradeId) {
        long start         = System.currentTimeMillis();
        long timeout       = props.getFillTimeoutMs();
        long pollInterval  = props.getFillPollIntervalMs();
        boolean marketSent = false;

        while (true) {
            OrderStatusResponse status;
            try {
                status = orderClient.getOrderStatus(orderId);
            } catch (UpstoxOrderException e) {
                log.error("execution.poll.error", kv("orderId", orderId), kv("error", e.getMessage()));
                return null;
            }

            if (status.isComplete()) {
                return toLegFill(orderId, correlationId, leg, status);
            }
            if (status.isRejected() || status.isCancelled()) {
                log.warn("execution.order.terminal",
                        kv("orderId", orderId),
                        kv("orderStatus", status.data() != null ? status.data().orderStatus() : "unknown"));
                return null;
            }

            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= timeout && !marketSent) {
                if (props.isCancelOnTimeoutInsteadOfMarket()) {
                    try { orderClient.cancelOrder(orderId); } catch (UpstoxOrderException ignore) {}
                    return null;
                } else {
                    try {
                        orderClient.modifyToMarket(orderId, leg.quantity());
                        marketSent = true;
                    } catch (UpstoxOrderException e) {
                        log.error("execution.modify.market.failed", kv("orderId", orderId));
                        return null;
                    }
                }
            }
            sleep(pollInterval);
        }
    }

    private void rollback(List<LegFillDto> filledLegs, String tag, UUID tradeId) {
        log.warn("execution.rollback.start",
                kv("tradeId", tradeId), kv("filledLegs", filledLegs.size()));

        for (int i = 0; i < filledLegs.size(); i++) {
            LegFillDto fill  = filledLegs.get(i);
            String reverse   = fill.action() == LegAction.SELL ? "BUY" : "SELL";
            String rbCorrId  = tag + "_RB_L" + i;
            try {
                orderClient.placeMultiOrder(new MultiOrderRequest(List.of(
                        MultiOrderRequest.OrderLeg.market(fill.instrumentKey(), reverse,
                                props.getProduct(), fill.quantityFilled(), tag + "_RB", rbCorrId))));
                log.info("execution.rollback.leg.placed",
                        kv("tradeId", tradeId), kv("instrument", fill.instrumentKey()));
            } catch (UpstoxOrderException e) {
                log.error("execution.rollback.leg.FAILED — MANUAL INTERVENTION REQUIRED",
                        kv("tradeId", tradeId), kv("instrument", fill.instrumentKey()),
                        kv("quantity", fill.quantityFilled()), kv("error", e.getMessage()));
            }
        }
    }

    private BigDecimal computeActualNet(List<LegFillDto> fills) {
        BigDecimal sellTotal = fills.stream()
                .filter(f -> f.action() == LegAction.SELL)
                .map(LegFillDto::averageFillPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal buyTotal = fills.stream()
                .filter(f -> f.action() == LegAction.BUY)
                .map(LegFillDto::averageFillPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sellTotal.subtract(buyTotal).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isSlippage(BigDecimal actual, BigDecimal expected) {
        if (expected == null || expected.signum() == 0) return false;
        BigDecimal threshold = props.getSlippageAlertThreshold();
        if (expected.signum() > 0) {
            return actual.compareTo(expected.multiply(BigDecimal.ONE.subtract(threshold))) < 0;
        } else {
            return actual.abs().compareTo(expected.abs().multiply(BigDecimal.ONE.add(threshold))) > 0;
        }
    }

    private LegFillDto toLegFill(String orderId, String correlationId,
                                  LegOrderRequest leg, OrderStatusResponse status) {
        BigDecimal avgPrice = status.data().averagePrice() != null
                ? status.data().averagePrice().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal slippage = leg.action() == LegAction.SELL
                ? leg.limitPrice().subtract(avgPrice)
                : avgPrice.subtract(leg.limitPrice());
        return new LegFillDto(orderId, correlationId, leg.instrumentKey(), leg.optionType(),
                leg.strike(), leg.action(), status.data().filledQuantity(),
                leg.limitPrice(), avgPrice, slippage.setScale(2, RoundingMode.HALF_UP));
    }

    private BigDecimal readExpectedNetPremium(UUID tradeId) {
        try {
            return jdbc.queryForObject(
                    "SELECT (summary->>'netPremiumPerUnit')::numeric FROM trades " +
                    "WHERE id = ? AND status = 'CONFIRMED'",
                    BigDecimal.class, tradeId);
        } catch (Exception e) {
            log.error("execution.db.read.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
            return null;
        }
    }

    private void persistFills(UUID tradeId, List<LegFillDto> fills) {
        try {
            String fillsJson = mapper.writeValueAsString(fills);
            jdbc.update("UPDATE trades SET status = 'ACTIVE', confirmed_at = NOW(), " +
                        "entry_fills = ?::jsonb WHERE id = ?", fillsJson, tradeId);
        } catch (JsonProcessingException | org.springframework.dao.DataAccessException e) {
            log.error("execution.persist.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
        }
    }

    private ExecuteTradeResponse rejected(UUID tradeId, BigDecimal expectedNet, String reason) {
        try {
            jdbc.update("UPDATE trades SET status = 'REJECTED', close_reason = ? WHERE id = ?",
                    reason, tradeId);
        } catch (Exception e) {
            log.error("execution.status.update.failed", kv("tradeId", tradeId));
        }
        return new ExecuteTradeResponse(tradeId, TradeStatus.REJECTED, List.of(),
                null, expectedNet, false, null, reason, LocalDateTime.now());
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
