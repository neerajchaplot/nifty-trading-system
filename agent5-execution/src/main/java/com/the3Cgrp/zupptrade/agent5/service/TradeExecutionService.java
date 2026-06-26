package com.the3Cgrp.zupptrade.agent5.service;

import tools.jackson.databind.json.JsonMapper;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient.UpstoxOrderException;
import com.the3Cgrp.zupptrade.agent5.client.request.MarginCheckRequest;
import com.the3Cgrp.zupptrade.agent5.client.request.MultiOrderRequest;
import com.the3Cgrp.zupptrade.agent5.client.response.FundsAndMarginResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MultiOrderResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.agent5.config.Agent5ExecutionProperties;
import com.the3Cgrp.zupptrade.agent5.dto.*;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.ledger.LedgerEventType;
import com.the3Cgrp.zupptrade.ledger.TradeLedgerService;
import com.the3Cgrp.zupptrade.ledger.payload.*;
import com.the3Cgrp.zupptrade.shared.dto.ExitTradeRequest;
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

    private final UpstoxOrderClient         orderClient;
    private final Agent5ExecutionProperties props;
    private final JdbcTemplate              jdbc;
    private final JsonMapper                mapper;
    private final AlertService              alertService;
    private final TradeLedgerService        ledger;

    public TradeExecutionService(UpstoxOrderClient orderClient,
                                 Agent5ExecutionProperties props,
                                 JdbcTemplate jdbc,
                                 JsonMapper mapper,
                                 AlertService alertService,
                                 TradeLedgerService ledger) {
        this.orderClient  = orderClient;
        this.props        = props;
        this.jdbc         = jdbc;
        this.mapper       = mapper;
        this.alertService = alertService;
        this.ledger       = ledger;
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
            return rejected(tradeId, null, "MARGIN_CHECK", "Trade not found or not in CONFIRMED status: " + tradeId);
        }

        // ── Step 1: Margin check — required margin from /v2/charges/margin ────
        MarginCheckResponse margin;
        try {
            margin = orderClient.checkMargin(buildMarginRequest(request));
        } catch (UpstoxOrderException e) {
            log.error("execution.margin.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
            return rejected(tradeId, expectedNet, "MARGIN_CHECK", "Margin check failed: " + e.getMessage());
        }

        BigDecimal requiredMargin = margin.data().finalMargin();

        // ── Step 1b: Available funds check — /v2/user/fund-and-margin ─────────
        if (props.isBypassMarginCheck()) {
            log.warn("execution.margin.bypassed",
                    kv("tradeId", tradeId), kv("required", requiredMargin),
                    kv("note", "bypass-margin-check=true — NEVER use in production"));
        } else {
            FundsAndMarginResponse funds;
            try {
                funds = orderClient.getAvailableFunds();
            } catch (UpstoxOrderException e) {
                log.error("execution.funds.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
                return rejected(tradeId, expectedNet, "MARGIN_CHECK", "Fund check failed: " + e.getMessage());
            }
            BigDecimal availableMargin = funds.availableMargin();
            if (availableMargin.compareTo(requiredMargin) < 0) {
                String reason = String.format("Insufficient margin. Required: ₹%.2f  Available: ₹%.2f",
                        requiredMargin, availableMargin);
                log.warn("execution.margin.insufficient", kv("tradeId", tradeId),
                        kv("required", requiredMargin), kv("available", availableMargin));
                return rejected(tradeId, expectedNet, "MARGIN_CHECK", reason);
            }
        }

        // ── Step 2: multi/place — all legs simultaneously ────────────────────
        // Upstox sandbox does not carry weekly NIFTY option contracts; simulate fills when flag set.
        if (props.isSimulateFills()) {
            return executeSimulated(tradeId, request, expectedNet);
        }

        MultiOrderResponse placed;
        try {
            placed = orderClient.placeMultiOrder(buildMultiOrderRequest(request, tag));
        } catch (UpstoxOrderException e) {
            log.error("execution.place.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
            return rejected(tradeId, expectedNet, "ORDER_PLACEMENT", "Order placement failed: " + e.getMessage());
        }

        // ── Step 3: Check payload validation result ──────────────────────────
        if (placed.hasPayloadErrors()) {
            String errors = placed.errors().stream()
                    .map(e -> e.correlationId() + ": " + e.message())
                    .collect(Collectors.joining("; "));
            log.warn("execution.payload.errors", kv("tradeId", tradeId), kv("errors", errors));
            return rejected(tradeId, expectedNet, "ORDER_PLACEMENT", "Payload error — no orders sent to exchange: " + errors);
        }

        // ── Ledger: TRADE_PLACED — orders are live on exchange ───────────────
        recordSilently(tradeId, LedgerEventType.TRADE_PLACED, buildTradePlacedPayload(request, placed),
                "AGENT5:SYSTEM");

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
            return rejected(tradeId, expectedNet, "ORDER_PLACEMENT", "Exchange rejected one or more legs: " + errors);
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
                return rejected(tradeId, expectedNet, "ORDER_PLACEMENT",
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
                return rejected(tradeId, expectedNet, "FILL_TIMEOUT",
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

        // Ledger: TRADE_EXECUTED — both legs filled, trade is ACTIVE
        recordSilently(tradeId, LedgerEventType.TRADE_EXECUTED,
                buildTradeExecutedPayload(fills, actualNet, slippage, slippageMsg),
                "AGENT5:SYSTEM");

        log.info("execution.complete", kv("tradeId", tradeId),
                kv("actualNet", actualNet), kv("slippageAlert", slippage));

        return new ExecuteTradeResponse(tradeId, TradeStatus.ACTIVE, fills,
                actualNet, expectedNet, slippage, slippageMsg, null, LocalDateTime.now());
    }

    // ── Exit ─────────────────────────────────────────────────────────────────

    public ExitTradeResponse exit(ExitTradeRequest request) {
        UUID tradeId = request.tradeId();
        String exitTag = OrderTagBuilder.exitTag(tradeId);

        log.info("exit.start", kv("tradeId", tradeId),
                kv("reason", request.reason()), kv("legCount", request.exitLegs().size()));

        // ── Guard: validate exit-eligible status ─────────────────────────────
        // Agent 3 sets EXIT_IN_PROGRESS before calling this endpoint (scheduler-side
        // dedup). We proceed if status is ACTIVE, EXIT_IN_PROGRESS, or EXIT_FAILED.
        // Any other status (CLOSED, REJECTED, PENDING_CONFIRM) means the exit is
        // not applicable — return without placing orders.
        TradeStatus current = readCurrentStatus(tradeId);
        if (current == null || (current != TradeStatus.ACTIVE &&
                                 current != TradeStatus.EXIT_IN_PROGRESS &&
                                 current != TradeStatus.EXIT_FAILED)) {
            log.warn("exit.invalid_status tradeId={} status={} — exit not applicable", tradeId, current);
            return new ExitTradeResponse(tradeId, current,
                    "Trade not in exit-eligible status: " + current, null);
        }

        // ── Ensure EXIT_IN_PROGRESS is set (idempotent if Agent 3 already set it) ──
        setTradeStatus(tradeId, TradeStatus.EXIT_IN_PROGRESS, null);

        // ── Build reverse MARKET multi-order ─────────────────────────────────
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
            String reason = "Exit order placement failed: " + e.getMessage();
            log.error("exit.place.failed — MANUAL INTERVENTION REQUIRED",
                    kv("tradeId", tradeId), kv("error", e.getMessage()));
            alertService.critical(tradeId, "exit_failed",
                    "Trade " + tradeId + " exit FAILED — position may still be open. " +
                    "MANUAL INTERVENTION REQUIRED. Error: " + e.getMessage());
            setTradeStatus(tradeId, TradeStatus.EXIT_FAILED, reason);
            recordSilently(tradeId, LedgerEventType.EXIT_FAILED,
                    new ExitFailedPayload("ORDER_PLACEMENT", reason, 1), "AGENT5:SYSTEM");
            return new ExitTradeResponse(tradeId, TradeStatus.EXIT_FAILED, reason, null);
        }

        // ── Check for payload validation errors ──────────────────────────────
        if (placed.hasPayloadErrors()) {
            String errors = placed.errors().stream()
                    .map(err -> err.correlationId() + ": " + err.message())
                    .collect(Collectors.joining("; "));
            String reason = "Exit payload error — orders not sent to exchange: " + errors;
            log.error("exit.payload.error", kv("tradeId", tradeId), kv("errors", errors));
            alertService.critical(tradeId, "exit_payload_error",
                    "Trade " + tradeId + " exit failed — payload rejected by Upstox before reaching exchange. " +
                    "MANUAL INTERVENTION REQUIRED. Errors: " + errors);
            setTradeStatus(tradeId, TradeStatus.EXIT_FAILED, reason);
            recordSilently(tradeId, LedgerEventType.EXIT_FAILED,
                    new ExitFailedPayload("ORDER_PLACEMENT", reason, 1), "AGENT5:SYSTEM");
            return new ExitTradeResponse(tradeId, TradeStatus.EXIT_FAILED, reason, null);
        }

        // ── Mark CLOSED — exit MARKET orders fill near-instantly ─────────────
        LocalDateTime closedAt = LocalDateTime.now();
        setTradeStatusClosed(tradeId, request.reason(), closedAt);
        recordSilently(tradeId, LedgerEventType.TRADE_CLOSED,
                new TradeClosedPayload(request.reason(), List.of(), null, null),
                "AGENT5:SYSTEM");

        log.info("exit.complete", kv("tradeId", tradeId), kv("reason", request.reason()),
                kv("success", placed.summary() != null ? placed.summary().success() : 0));

        return new ExitTradeResponse(tradeId, TradeStatus.CLOSED, null, closedAt);
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

    // Detect spread direction from actual fill net (SELL_total - BUY_total):
    //   Credit spread (received) → actual > 0 → slippage when actual < expected × (1 - threshold)
    //   Debit spread (paid out)  → actual < 0 → slippage when abs(actual) > abs(expected) × (1 + threshold)
    // Using actual sign (not expected sign) because Agent 2 always stores netPremiumPerUnit
    // as a positive magnitude for both credit and debit spreads.
    private boolean isSlippage(BigDecimal actual, BigDecimal expected) {
        if (expected == null || expected.signum() == 0) return false;
        BigDecimal threshold = props.getSlippageAlertThreshold();
        if (actual.signum() >= 0) {
            // Credit spread — slippage means receiving less than expected
            return actual.compareTo(expected.abs().multiply(BigDecimal.ONE.subtract(threshold))) < 0;
        } else {
            // Debit spread — slippage means paying more than expected
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

    // ── Simulate fills (sandbox only) ─────────────────────────────────────────

    /**
     * Bypasses Upstox order placement entirely.
     * Builds synthetic fills at each leg's limitPrice (zero slippage), then runs the
     * normal downstream path: slippage check → persistFills → ACTIVE.
     * Allows end-to-end execution flow testing when the sandbox doesn't support the instrument.
     */
    private ExecuteTradeResponse executeSimulated(UUID tradeId, ExecuteTradeRequest request,
                                                  BigDecimal expectedNet) {
        log.warn("execution.fills.simulated",
                kv("tradeId", tradeId),
                kv("legCount", request.legs().size()),
                kv("note", "simulate-fills=true — NEVER use in production"));

        List<LegFillDto> fills = buildSimulatedFills(request);

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

        persistFills(tradeId, fills);

        recordSilently(tradeId, LedgerEventType.TRADE_EXECUTED,
                buildTradeExecutedPayload(fills, actualNet, slippage, slippageMsg),
                "AGENT5:SIMULATE");

        log.info("execution.simulated.complete", kv("tradeId", tradeId),
                kv("actualNet", actualNet), kv("slippageAlert", slippage));

        return new ExecuteTradeResponse(tradeId, TradeStatus.ACTIVE, fills,
                actualNet, expectedNet, slippage, slippageMsg, null, LocalDateTime.now());
    }

    private List<LegFillDto> buildSimulatedFills(ExecuteTradeRequest request) {
        List<LegFillDto> fills = new ArrayList<>();
        for (int i = 0; i < request.legs().size(); i++) {
            LegOrderRequest leg = request.legs().get(i);
            String simulatedOrderId = "SIM-" + request.tradeId().toString().substring(0, 8).toUpperCase() + "-L" + i;
            fills.add(new LegFillDto(
                    simulatedOrderId,
                    OrderTagBuilder.correlationId(request.tradeId(), i),
                    leg.instrumentKey(),
                    leg.optionType(),
                    leg.strike(),
                    leg.action(),
                    leg.quantity(),
                    leg.limitPrice(),
                    leg.limitPrice(),   // averageFillPrice = limitPrice (perfect fill, zero slippage)
                    BigDecimal.ZERO));  // slippagePerUnit = 0
        }
        return fills;
    }

    private void persistFills(UUID tradeId, List<LegFillDto> fills) {
        try {
            String fillsJson = mapper.writeValueAsString(fills);
            jdbc.update("UPDATE trades SET status = 'ACTIVE', confirmed_at = NOW(), " +
                        "entry_fills = ?::jsonb WHERE id = ?", fillsJson, tradeId);
        } catch (Exception e) {
            log.error("execution.persist.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
        }
    }

    private ExecuteTradeResponse rejected(UUID tradeId, BigDecimal expectedNet,
                                           String failureStage, String reason) {
        try {
            jdbc.update("UPDATE trades SET status = 'REJECTED', close_reason = ? WHERE id = ?",
                    reason, tradeId);
        } catch (Exception e) {
            log.error("execution.status.update.failed", kv("tradeId", tradeId));
        }
        recordSilently(tradeId, LedgerEventType.TRADE_FAILED,
                new TradeFailedPayload(failureStage, reason, null), "AGENT5:SYSTEM");
        return new ExecuteTradeResponse(tradeId, TradeStatus.REJECTED, List.of(),
                null, expectedNet, false, null, reason, LocalDateTime.now());
    }

    // ── Ledger helpers ────────────────────────────────────────────────────────

    /**
     * Records a ledger event without propagating failures.
     * Used for post-Upstox events where trade state on exchange takes precedence.
     */
    private void recordSilently(UUID tradeId, LedgerEventType eventType,
                                  Object payload, String occurredBy) {
        try {
            ledger.record(tradeId, eventType, payload, occurredBy);
        } catch (Exception e) {
            log.error("ledger.record.failed — AUDIT GAP. tradeId={} event={} error={}",
                    tradeId, eventType, e.getMessage());
            alertService.critical(tradeId, "ledger_write_failed",
                    "Ledger write failed for event " + eventType + " on trade " + tradeId +
                    ". Audit trail has a gap. DB error: " + e.getMessage());
        }
    }

    private TradePlacedPayload buildTradePlacedPayload(ExecuteTradeRequest request,
                                                        MultiOrderResponse placed) {
        List<TradePlacedPayload.LegOrder> legOrders = new ArrayList<>();
        for (int i = 0; i < request.legs().size(); i++) {
            LegOrderRequest leg = request.legs().get(i);
            String corrId  = OrderTagBuilder.correlationId(request.tradeId(), i);
            String orderId = placed.data() != null
                    ? placed.data().stream()
                        .filter(d -> d.correlationId().equals(corrId))
                        .findFirst().map(d -> d.orderId()).orElse(null)
                    : null;
            legOrders.add(new TradePlacedPayload.LegOrder(corrId, orderId,
                    leg.instrumentKey(), leg.action().name(), leg.quantity()));
        }
        return new TradePlacedPayload(legOrders);
    }

    private TradeExecutedPayload buildTradeExecutedPayload(List<LegFillDto> fills,
                                                             BigDecimal actualNet,
                                                             boolean slippageAlert,
                                                             String slippageMsg) {
        List<TradeExecutedPayload.LegFill> legFills = fills.stream()
                .map(f -> new TradeExecutedPayload.LegFill(
                        f.orderId(), f.instrumentKey(), f.action().name(),
                        f.quantityFilled(), f.averageFillPrice()))
                .toList();
        return new TradeExecutedPayload(legFills, actualNet, slippageAlert, slippageMsg);
    }

    private TradeStatus readCurrentStatus(UUID tradeId) {
        try {
            String status = jdbc.queryForObject(
                    "SELECT status FROM trades WHERE id = ?", String.class, tradeId);
            return status != null ? TradeStatus.valueOf(status) : null;
        } catch (Exception e) {
            log.error("exit.status.read.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
            return null;
        }
    }

    private void setTradeStatus(UUID tradeId, TradeStatus status, String closeReason) {
        try {
            if (closeReason != null) {
                jdbc.update("UPDATE trades SET status = ?, close_reason = ? WHERE id = ?",
                        status.name(), closeReason, tradeId);
            } else {
                jdbc.update("UPDATE trades SET status = ? WHERE id = ?", status.name(), tradeId);
            }
        } catch (Exception e) {
            log.error("exit.status.update.failed",
                    kv("tradeId", tradeId), kv("status", status), kv("error", e.getMessage()));
        }
    }

    private void setTradeStatusClosed(UUID tradeId, String closeReason, LocalDateTime closedAt) {
        try {
            jdbc.update("UPDATE trades SET status = 'CLOSED', closed_at = NOW(), close_reason = ? WHERE id = ?",
                    closeReason, tradeId);
        } catch (Exception e) {
            log.error("exit.closed.update.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
