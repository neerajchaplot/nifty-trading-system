package com.the3Cgrp.zupptrade.agent5.service;

import tools.jackson.databind.json.JsonMapper;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient;
import com.the3Cgrp.zupptrade.agent5.client.UpstoxOrderClient.UpstoxOrderException;
import com.the3Cgrp.zupptrade.agent5.client.request.MarginCheckRequest;
import com.the3Cgrp.zupptrade.agent5.client.request.PlaceOrderV3Request;
import com.the3Cgrp.zupptrade.agent5.client.response.FundsAndMarginResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.PlaceOrderV3Response;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse;
import com.the3Cgrp.zupptrade.agent5.config.Agent5ExecutionProperties;
import com.the3Cgrp.zupptrade.agent5.dto.*;
import com.the3Cgrp.zupptrade.core.alert.AlertService;
import com.the3Cgrp.zupptrade.core.alert.CriticalAlertService;
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

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Orchestrates the full trade execution lifecycle on the Upstox v3 order API.
 *
 * Entry flow (sequential, protective-leg-first):
 *   1. Read expected net premium from DB (tradeId → trades table, status = CONFIRMED)
 *   2. Reject any leg whose quantity exceeds the exchange freeze limit (no auto-slicing)
 *   3. Margin check (all legs together — SPAN spread benefit) + available-funds check
 *   4. Place legs ONE AT A TIME via POST /v3/order/place, each with a UNIQUE per-leg tag
 *      (the tag replaces v2's correlation_id). Poll each to fill before placing the next.
 *   5. Any leg failure → roll back already-filled legs (reverse MARKET) and stop.
 *      Ambiguous (5xx/timeout) placement failure → reconcile by tag and drive to flat.
 *   6. Slippage check — alert only, trade stays live.
 *   7. Persist fills to trades.entry_fills, set status ACTIVE.
 *
 * Exit flow: place a reverse MARKET order per leg (BUY→SELL, SELL→BUY), each with its own tag.
 */
@Service
public class TradeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionService.class);

    private final UpstoxOrderClient         orderClient;
    private final Agent5ExecutionProperties props;
    private final JdbcTemplate              jdbc;
    private final JsonMapper                mapper;
    private final AlertService              alertService;
    private final CriticalAlertService      criticalAlertService;
    private final TradeLedgerService        ledger;

    public TradeExecutionService(UpstoxOrderClient orderClient,
                                 Agent5ExecutionProperties props,
                                 JdbcTemplate jdbc,
                                 JsonMapper mapper,
                                 AlertService alertService,
                                 CriticalAlertService criticalAlertService,
                                 TradeLedgerService ledger) {
        this.orderClient          = orderClient;
        this.props                = props;
        this.jdbc                 = jdbc;
        this.mapper               = mapper;
        this.alertService         = alertService;
        this.criticalAlertService = criticalAlertService;
        this.ledger               = ledger;
    }

    // ── Entry ───────────────────────────────────────────────────────────────

    public ExecuteTradeResponse execute(ExecuteTradeRequest request) {
        UUID tradeId = request.tradeId();

        log.info("execution.start", kv("tradeId", tradeId), kv("legCount", request.legs().size()));

        // Read expected net premium from DB — Agent 5 never trusts caller for financial figures.
        // Query is gated on status = 'CONFIRMED'; null means the trade is not CONFIRMED — reject
        // WITHOUT changing the trade's DB status (it is in a valid state; this is just an invalid attempt).
        BigDecimal expectedNet = readExpectedNetPremium(tradeId);
        if (expectedNet == null) {
            log.warn("execution.not.confirmed tradeId={} — execution rejected, trade status unchanged", tradeId);
            return new ExecuteTradeResponse(tradeId, TradeStatus.REJECTED, List.of(),
                    null, null, false, null,
                    "Trade not found or not in CONFIRMED status: " + tradeId,
                    LocalDateTime.now());
        }

        // ── Step 1: Reject oversized legs (no auto-slicing) ──────────────────
        // Upstox rejects a single order above the exchange freeze quantity. We do NOT slice;
        // a leg over the limit is rejected up front so the user splits it into two orders.
        for (int i = 0; i < request.legs().size(); i++) {
            int qty = request.legs().get(i).quantity();
            if (qty > props.getMaxOrderQuantity()) {
                String reason = "Leg " + i + " quantity " + qty + " exceeds the max order quantity "
                        + props.getMaxOrderQuantity() + " (exchange freeze limit). Split into smaller orders.";
                log.warn("execution.quantity.exceeds_freeze",
                        kv("tradeId", tradeId), kv("legIndex", i), kv("quantity", qty),
                        kv("max", props.getMaxOrderQuantity()));
                return rejected(tradeId, expectedNet, "QUANTITY_LIMIT", reason);
            }
        }

        // ── Step 2: Margin check — required margin from /v2/charges/margin ────
        MarginCheckResponse margin;
        try {
            margin = orderClient.checkMargin(buildMarginRequest(request));
        } catch (UpstoxOrderException e) {
            log.error("execution.margin.failed", kv("tradeId", tradeId), kv("error", e.getMessage()));
            return rejected(tradeId, expectedNet, "MARGIN_CHECK", "Margin check failed: " + e.getMessage());
        }
        BigDecimal requiredMargin = margin.data().finalMargin();

        // ── Step 2b: Available funds check — /v2/user/get-funds-and-margin ────
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

        // Sandbox may not fill weekly NIFTY on a resting LIMIT — simulate fills when flag set.
        if (props.isSimulateFills()) {
            return executeSimulated(tradeId, request, expectedNet);
        }

        // ── Step 3: Sequential placement — protective (BUY) legs first ───────
        // One leg per call (place → poll to fill → next). At any failure, the only live orders are
        // the ones already filled (tracked in filledInPlacementOrder) — reverse them and stop.
        LegFillDto[] fillsByIndex = new LegFillDto[request.legs().size()];
        List<LegFillDto> filledInPlacementOrder = new ArrayList<>();

        for (int legIndex : protectiveFirstOrder(request.legs())) {
            LegOrderRequest leg = request.legs().get(legIndex);
            String legTag       = OrderTagBuilder.entryTag(tradeId, legIndex);

            PlaceOrderV3Response placed;
            try {
                placed = orderClient.placeOrder(PlaceOrderV3Request.limit(
                        leg.instrumentKey(), leg.action().name(), props.getProduct(),
                        leg.quantity(), leg.limitPrice(), legTag));
            } catch (UpstoxOrderException e) {
                if (e.isAmbiguous()) {
                    // 5xx/timeout — the order may or may not have reached the exchange. We do NOT
                    // retry (would risk a duplicate). Reconcile by tag and drive to flat.
                    log.error("execution.place.ambiguous_failure",
                            kv("tradeId", tradeId), kv("legIndex", legIndex), kv("error", e.getMessage()));
                    return reconcileAndFlatten(tradeId, request, filledInPlacementOrder,
                            legIndex, e.getMessage(), expectedNet);
                }
                // Deterministic 4xx — nothing was placed for this leg. Roll back prior fills, reject.
                log.warn("execution.leg.rejected",
                        kv("tradeId", tradeId), kv("legIndex", legIndex), kv("error", e.getMessage()));
                rollback(filledInPlacementOrder, tradeId);
                return rejected(tradeId, expectedNet, "ORDER_PLACEMENT",
                        "Leg " + legIndex + " rejected: " + e.getMessage() + ". Filled legs rolled back.");
            }

            // We never slice, so expect exactly one order_id. Any other count is unexpected — an
            // order MAY exist, so reconcile by tag rather than assume nothing happened.
            String orderId = placed.singleOrderId();
            if (orderId == null) {
                log.error("execution.order.id.unexpected",
                        kv("tradeId", tradeId), kv("legIndex", legIndex), kv("orderIds", placed.orderIds()));
                return reconcileAndFlatten(tradeId, request, filledInPlacementOrder,
                        legIndex, "unexpected order_ids: " + placed.orderIds(), expectedNet);
            }

            // Ledger: TRADE_PLACED — this leg is live on the exchange
            recordSilently(tradeId, LedgerEventType.TRADE_PLACED,
                    new TradePlacedPayload(List.of(new TradePlacedPayload.LegOrder(
                            legTag, orderId, leg.instrumentKey(), leg.action().name(), leg.quantity()))),
                    "AGENT5:SYSTEM");

            // Poll this leg to a fill before placing the next
            PollResult poll = pollToCompletion(orderId, leg, legTag, tradeId);
            if (!poll.fullyFilled()) {
                // If it PARTIALLY filled, that position is real and must be compensated too —
                // include it so rollback reverses exactly the filled qty, not the ordered qty.
                if (poll.fill() != null) {
                    filledInPlacementOrder.add(poll.fill());
                    log.warn("execution.leg.partial",
                            kv("tradeId", tradeId), kv("legIndex", legIndex),
                            kv("filled", poll.fill().quantityFilled()), kv("ordered", leg.quantity()));
                }
                log.warn("execution.leg.failed",
                        kv("tradeId", tradeId), kv("legIndex", legIndex), kv("orderId", orderId));
                rollback(filledInPlacementOrder, tradeId);
                return rejected(tradeId, expectedNet, "FILL_TIMEOUT",
                        "Leg " + legIndex + " (orderId=" + orderId + ") did not fully fill. "
                                + "Filled legs (including partial fills) rolled back.");
            }
            fillsByIndex[legIndex] = poll.fill();
            filledInPlacementOrder.add(poll.fill());
        }

        // ── Step 4: All legs filled — persist in original leg order ──────────
        List<LegFillDto> fills = Arrays.stream(fillsByIndex).filter(Objects::nonNull).toList();

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
                "AGENT5:SYSTEM");

        log.info("execution.complete", kv("tradeId", tradeId),
                kv("actualNet", actualNet), kv("slippageAlert", slippage));

        return new ExecuteTradeResponse(tradeId, TradeStatus.ACTIVE, fills,
                actualNet, expectedNet, slippage, slippageMsg, null, LocalDateTime.now());
    }

    // ── Exit ─────────────────────────────────────────────────────────────────

    public ExitTradeResponse exit(ExitTradeRequest request) {
        UUID tradeId = request.tradeId();

        log.info("exit.start", kv("tradeId", tradeId),
                kv("reason", request.reason()), kv("legCount", request.exitLegs().size()));

        // ── Guard: validate exit-eligible status ─────────────────────────────
        TradeStatus current = readCurrentStatus(tradeId);
        if (current == null || (current != TradeStatus.ACTIVE &&
                                 current != TradeStatus.EXIT_IN_PROGRESS &&
                                 current != TradeStatus.EXIT_FAILED)) {
            log.warn("exit.invalid_status tradeId={} status={} — exit not applicable", tradeId, current);
            return new ExitTradeResponse(tradeId, current,
                    "Trade not in exit-eligible status: " + current, null);
        }

        // ── Simulate exit (sandbox only) — skip Upstox entirely, mark CLOSED ──
        if (props.isSimulateExit()) {
            log.warn("exit.fills.simulated tradeId={} — simulate-exit=true, NEVER use in production", tradeId);
            LocalDateTime closedAt = LocalDateTime.now();
            setTradeStatusClosed(tradeId, request.reason(), closedAt);
            recordSilently(tradeId, LedgerEventType.TRADE_CLOSED,
                    new TradeClosedPayload(request.reason(), List.of(), null, null),
                    "AGENT5:SIMULATE");
            log.info("exit.simulated.complete", kv("tradeId", tradeId), kv("reason", request.reason()));
            return new ExitTradeResponse(tradeId, TradeStatus.CLOSED, null, closedAt);
        }

        // Ensure EXIT_IN_PROGRESS is set (idempotent if Agent 3 already set it).
        setTradeStatus(tradeId, TradeStatus.EXIT_IN_PROGRESS, null);

        // ── Place a reverse MARKET order per leg (one call each) ─────────────
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < request.exitLegs().size(); i++) {
            ExitTradeRequest.ExitLeg leg = request.exitLegs().get(i);
            String reverse = leg.originalAction() == LegAction.SELL ? "BUY" : "SELL";
            try {
                orderClient.placeOrder(PlaceOrderV3Request.market(
                        leg.instrumentKey(), reverse, props.getProduct(),
                        leg.quantity(), OrderTagBuilder.exitTag(tradeId, i)));
                log.info("exit.leg.placed", kv("tradeId", tradeId), kv("legIndex", i),
                        kv("instrument", leg.instrumentKey()), kv("reverse", reverse));
            } catch (UpstoxOrderException e) {
                failures.add("leg " + i + " (" + leg.instrumentKey() + "): " + e.getMessage());
                log.error("exit.leg.failed", kv("tradeId", tradeId), kv("legIndex", i),
                        kv("instrument", leg.instrumentKey()), kv("error", e.getMessage()));
            }
        }

        if (!failures.isEmpty()) {
            String reason = "Exit order placement failed for: " + String.join("; ", failures);
            log.error("exit.failed — MANUAL INTERVENTION REQUIRED", kv("tradeId", tradeId), kv("failures", reason));
            alertService.critical(tradeId, "exit_failed",
                    "Trade " + tradeId + " exit FAILED — position may still be open. " +
                    "MANUAL INTERVENTION REQUIRED. " + reason);
            setTradeStatus(tradeId, TradeStatus.EXIT_FAILED, reason);
            recordSilently(tradeId, LedgerEventType.EXIT_FAILED,
                    new ExitFailedPayload("ORDER_PLACEMENT", reason, failures.size()), "AGENT5:SYSTEM");
            return new ExitTradeResponse(tradeId, TradeStatus.EXIT_FAILED, reason, null);
        }

        // ── All reverse orders placed — mark CLOSED (MARKET orders fill near-instantly) ──
        LocalDateTime closedAt = LocalDateTime.now();
        setTradeStatusClosed(tradeId, request.reason(), closedAt);
        recordSilently(tradeId, LedgerEventType.TRADE_CLOSED,
                new TradeClosedPayload(request.reason(), List.of(), null, null),
                "AGENT5:SYSTEM");

        log.info("exit.complete", kv("tradeId", tradeId), kv("reason", request.reason()),
                kv("legs", request.exitLegs().size()));

        return new ExitTradeResponse(tradeId, TradeStatus.CLOSED, null, closedAt);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Placement order: protective (BUY) legs first, premium-collecting (SELL) legs last.
     * Returns original leg indices in that order (stable within each group).
     */
    private List<Integer> protectiveFirstOrder(List<LegOrderRequest> legs) {
        List<Integer> buys  = new ArrayList<>();
        List<Integer> sells = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            if (legs.get(i).action() == LegAction.BUY) buys.add(i); else sells.add(i);
        }
        buys.addAll(sells);
        return buys;
    }

    private MarginCheckRequest buildMarginRequest(ExecuteTradeRequest request) {
        List<MarginCheckRequest.Instrument> instruments = request.legs().stream()
                .map(leg -> new MarginCheckRequest.Instrument(
                        leg.instrumentKey(), leg.action().name(),
                        leg.quantity(), props.getProduct(), leg.limitPrice()))
                .toList();
        return new MarginCheckRequest(instruments);
    }

    /**
     * Outcome of polling one leg to a terminal state.
     * `fill` is present whenever ANY quantity filled — full OR partial — so a partial fill can be
     * compensated by reversing exactly {@code fill.quantityFilled()}. `fullyFilled` distinguishes
     * a complete fill from a partial one.
     */
    private record PollResult(boolean fullyFilled, LegFillDto fill) {
        static PollResult full(LegFillDto f)    { return new PollResult(true,  f); }
        static PollResult partial(LegFillDto f) { return new PollResult(false, f); }
        static PollResult none()                { return new PollResult(false, null); }
    }

    private PollResult pollToCompletion(String orderId, LegOrderRequest leg,
                                        String legTag, UUID tradeId) {
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
                return PollResult.none();
            }

            if (status.isComplete()) {
                return PollResult.full(toLegFill(orderId, legTag, leg, status));
            }
            if (status.isRejected() || status.isCancelled()) {
                log.warn("execution.order.terminal",
                        kv("orderId", orderId),
                        kv("orderStatus", status.data() != null ? status.data().orderStatus() : "unknown"));
                return partialOrNone(orderId, legTag, leg, status);
            }

            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= timeout && !marketSent) {
                if (props.isCancelOnTimeoutInsteadOfMarket()) {
                    try { orderClient.cancelOrder(orderId); } catch (UpstoxOrderException ignore) {}
                    return partialOrNone(orderId, legTag, leg, safeStatus(orderId));
                } else {
                    try {
                        orderClient.modifyToMarket(orderId, leg.quantity());
                        marketSent = true;
                    } catch (UpstoxOrderException e) {
                        log.error("execution.modify.market.failed", kv("orderId", orderId));
                        return partialOrNone(orderId, legTag, leg, safeStatus(orderId));
                    }
                }
            }
            sleep(pollInterval);
        }
    }

    /**
     * Builds a PARTIAL-fill result from a terminal/cancelled status, or none() if nothing filled.
     * The LegFillDto carries the ACTUAL filled quantity, so rollback reverses exactly what filled.
     */
    private PollResult partialOrNone(String orderId, String legTag,
                                     LegOrderRequest leg, OrderStatusResponse status) {
        int filled = (status != null && status.data() != null) ? status.data().filledQuantity() : 0;
        if (filled <= 0) {
            return PollResult.none();
        }
        log.warn("execution.leg.partial_fill",
                kv("orderId", orderId), kv("filled", filled), kv("ordered", leg.quantity()));
        return PollResult.partial(toLegFill(orderId, legTag, leg, status));
    }

    /** Best-effort final status read — returns null if Upstox is unreachable. */
    private OrderStatusResponse safeStatus(String orderId) {
        try {
            return orderClient.getOrderStatus(orderId);
        } catch (UpstoxOrderException e) {
            log.error("execution.final_status.read.failed",
                    kv("orderId", orderId), kv("error", e.getMessage()));
            return null;
        }
    }

    /** Reverses each already-filled leg with a MARKET order at its actual filled quantity. */
    private void rollback(List<LegFillDto> filledLegs, UUID tradeId) {
        if (filledLegs.isEmpty()) return;
        log.warn("execution.rollback.start", kv("tradeId", tradeId), kv("filledLegs", filledLegs.size()));

        for (int i = 0; i < filledLegs.size(); i++) {
            LegFillDto fill = filledLegs.get(i);
            String reverse  = fill.action() == LegAction.SELL ? "BUY" : "SELL";
            try {
                orderClient.placeOrder(PlaceOrderV3Request.market(
                        fill.instrumentKey(), reverse, props.getProduct(),
                        fill.quantityFilled(), OrderTagBuilder.rollbackTag(tradeId, i)));
                log.info("execution.rollback.leg.placed",
                        kv("tradeId", tradeId), kv("instrument", fill.instrumentKey()));
            } catch (UpstoxOrderException e) {
                log.error("execution.rollback.leg.FAILED — MANUAL INTERVENTION REQUIRED",
                        kv("tradeId", tradeId), kv("instrument", fill.instrumentKey()),
                        kv("quantity", fill.quantityFilled()), kv("error", e.getMessage()));
                alertService.critical(tradeId, "rollback_failed",
                        "Rollback of filled leg " + fill.instrumentKey() + " (qty " +
                        fill.quantityFilled() + ") failed for trade " + tradeId +
                        ". Position may still be open — MANUAL INTERVENTION REQUIRED. Error: " + e.getMessage());
            }
        }
    }

    // ── Ambiguous-placement-failure reconciliation ───────────────────────────

    /** One position to flatten — from an in-memory fill or the order book, keyed by order_id. */
    private record ReconcileItem(String orderId, String instrumentKey,
                                 LegAction action, boolean openInBook, int knownFilledQty) {}

    /**
     * Recovers from an AMBIGUOUS placement failure (5xx/timeout) on the leg at failedLegIndex, where
     * we cannot know whether the order reached the exchange. Retrying would risk a duplicate, so we
     * ask the exchange what exists under that leg's unique tag and drive everything flat:
     *   wait → query order book by the failed leg's tag → union with in-memory known fills (prior
     *   legs) keyed by order_id → for each: cancel-if-open, re-read, reverse the confirmed fill.
     *
     * Every reconciliation records a critical_alert with a transparent JSON snapshot. Status becomes
     * REJECTED only when we are confident the position is flat; otherwise RECONCILE_REQUIRED.
     */
    private ExecuteTradeResponse reconcileAndFlatten(UUID tradeId, ExecuteTradeRequest request,
                                                     List<LegFillDto> knownFills, int failedLegIndex,
                                                     String triggerError, BigDecimal expectedNet) {
        sleep(props.getReconcileDelayMs());
        String failedTag = OrderTagBuilder.entryTag(tradeId, failedLegIndex);

        // 1. Query the order book by the failed leg's tag (idempotent GET — retried in the client).
        TaggedOrdersResponse book;
        try {
            book = orderClient.getOrderDetailsByTag(failedTag);
        } catch (UpstoxOrderException e) {
            String reason = "Order placement failed AND reconciliation query failed for tag " + failedTag +
                    " — position state UNKNOWN. Check the Upstox order book for tag " + failedTag +
                    " and clean up manually.";
            criticalAlertService.record(tradeId, reason,
                    reconcileSnapshot(tradeId, failedTag, request, knownFills, failedLegIndex,
                            triggerError, "tag query failed: " + e.getMessage(), List.of()));
            log.error("execution.reconcile.query_failed — MANUAL INTERVENTION REQUIRED",
                    kv("tradeId", tradeId), kv("tag", failedTag), kv("error", e.getMessage()));
            setTradeStatus(tradeId, TradeStatus.RECONCILE_REQUIRED, "Reconcile query failed: " + e.getMessage());
            recordSilently(tradeId, LedgerEventType.TRADE_FAILED,
                    new TradeFailedPayload("RECONCILE_QUERY_FAILED", triggerError, null), "AGENT5:SYSTEM");
            return reconcileResponse(tradeId, TradeStatus.RECONCILE_REQUIRED, expectedNet, reason);
        }

        // 2. Flatten set keyed by order_id: in-memory prior fills + the failed leg's book orders.
        Map<String, ReconcileItem> toFlatten = new LinkedHashMap<>();
        for (LegFillDto f : knownFills) {
            if (f.orderId() != null) {
                toFlatten.put(f.orderId(), new ReconcileItem(
                        f.orderId(), f.instrumentKey(), f.action(), false, f.quantityFilled()));
            }
        }
        for (TaggedOrdersResponse.TaggedOrder o : book.orders()) {
            if (o.tag() == null || !failedTag.equals(o.tag()) || o.orderId() == null) continue;
            toFlatten.put(o.orderId(), new ReconcileItem(
                    o.orderId(), o.instrumentToken(),
                    "BUY".equalsIgnoreCase(o.transactionType()) ? LegAction.BUY : LegAction.SELL,
                    o.isOpen(), o.filledQuantity()));
        }

        // 3. Nothing found under the tag AND no prior fills — suspicious after a 5xx.
        if (toFlatten.isEmpty()) {
            String reason = "nothing found: doesn't sound right, go and check for trade_id " + tradeId +
                    " (tag " + failedTag + ") and clean please";
            criticalAlertService.record(tradeId, reason,
                    reconcileSnapshot(tradeId, failedTag, request, knownFills, failedLegIndex,
                            triggerError, "no orders found under tag", List.of()));
            log.error("execution.reconcile.nothing_found — MANUAL CHECK REQUIRED",
                    kv("tradeId", tradeId), kv("tag", failedTag));
            setTradeStatus(tradeId, TradeStatus.RECONCILE_REQUIRED, "Placement failed; nothing found under tag");
            recordSilently(tradeId, LedgerEventType.TRADE_FAILED,
                    new TradeFailedPayload("RECONCILE_NOTHING_FOUND", triggerError, null), "AGENT5:SYSTEM");
            return reconcileResponse(tradeId, TradeStatus.RECONCILE_REQUIRED, expectedNet, reason);
        }

        // 4. Drive each found order to flat: cancel-if-open → re-read → reverse confirmed fill.
        List<Map<String, Object>> actions = new ArrayList<>();
        boolean allClean = true;
        int rbSeq = 0;
        for (ReconcileItem item : toFlatten.values()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("orderId", item.orderId());
            a.put("instrumentKey", item.instrumentKey());
            try {
                if (item.openInBook()) {
                    orderClient.cancelOrder(item.orderId());
                    a.put("cancelled", true);
                }
                int filled = item.knownFilledQty();
                OrderStatusResponse fs = safeStatus(item.orderId());
                if (fs != null && fs.data() != null) filled = fs.data().filledQuantity();
                a.put("filledQty", filled);
                if (filled > 0) {
                    String reverse = item.action() == LegAction.SELL ? "BUY" : "SELL";
                    orderClient.placeOrder(PlaceOrderV3Request.market(
                            item.instrumentKey(), reverse, props.getProduct(),
                            filled, OrderTagBuilder.rollbackTag(tradeId, rbSeq++)));
                    a.put("reversed", true);
                    a.put("reverseAction", reverse);
                } else {
                    a.put("reversed", false);
                }
            } catch (UpstoxOrderException ex) {
                allClean = false;
                a.put("error", ex.getMessage());
                log.error("execution.reconcile.item.FAILED — MANUAL INTERVENTION REQUIRED",
                        kv("tradeId", tradeId), kv("orderId", item.orderId()),
                        kv("instrument", item.instrumentKey()), kv("error", ex.getMessage()));
            }
            actions.add(a);
        }

        // 5. Always record a critical alert — a live position may have existed and we made adjustments.
        String reason = allClean
                ? "Order placement failed (ambiguous); reconciled trade " + tradeId +
                  " to flat by reversing filled legs. Review the adjustments."
                : "Order placement failed (ambiguous); reconciliation of trade " + tradeId +
                  " did NOT complete cleanly — a position may still be open. MANUAL INTERVENTION REQUIRED.";
        criticalAlertService.record(tradeId, reason,
                reconcileSnapshot(tradeId, failedTag, request, knownFills, failedLegIndex, triggerError,
                        allClean ? "reconciled to flat" : "reconciliation incomplete", actions));

        TradeStatus finalStatus = allClean ? TradeStatus.REJECTED : TradeStatus.RECONCILE_REQUIRED;
        setTradeStatus(tradeId, finalStatus,
                allClean ? "Ambiguous placement failure; reconciled flat"
                         : "Ambiguous placement failure; reconcile incomplete");
        recordSilently(tradeId, LedgerEventType.TRADE_FAILED,
                new TradeFailedPayload(allClean ? "RECONCILED_FLAT" : "RECONCILE_INCOMPLETE", triggerError, null),
                "AGENT5:SYSTEM");

        return reconcileResponse(tradeId, finalStatus, expectedNet, reason);
    }

    private ExecuteTradeResponse reconcileResponse(UUID tradeId, TradeStatus status,
                                                   BigDecimal expectedNet, String reason) {
        return new ExecuteTradeResponse(tradeId, status, List.of(),
                null, expectedNet, false, null, reason, LocalDateTime.now());
    }

    /** Transparent snapshot of the trade state as we knew it — stored as critical_alert JSON. */
    private Map<String, Object> reconcileSnapshot(UUID tradeId, String failedTag, ExecuteTradeRequest request,
                                                  List<LegFillDto> knownFills, int failedLegIndex,
                                                  String triggerError, String outcome, List<?> actions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradeId", tradeId.toString());
        m.put("failedLegTag", failedTag);
        m.put("triggerError", triggerError);
        m.put("failedLegIndex", failedLegIndex);
        m.put("outcome", outcome);
        m.put("attemptedLegs", request.legs().stream().map(l -> {
            Map<String, Object> leg = new LinkedHashMap<>();
            leg.put("instrumentKey", l.instrumentKey());
            leg.put("action", l.action().name());
            leg.put("quantity", l.quantity());
            leg.put("limitPrice", l.limitPrice());
            return leg;
        }).toList());
        m.put("knownFilledLegs", knownFills.stream().map(f -> {
            Map<String, Object> leg = new LinkedHashMap<>();
            leg.put("orderId", f.orderId());
            leg.put("instrumentKey", f.instrumentKey());
            leg.put("action", f.action().name());
            leg.put("quantityFilled", f.quantityFilled());
            return leg;
        }).toList());
        m.put("reconcileActions", actions);
        m.put("recordedAt", LocalDateTime.now().toString());
        return m;
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
    private boolean isSlippage(BigDecimal actual, BigDecimal expected) {
        if (expected == null || expected.signum() == 0) return false;
        BigDecimal threshold = props.getSlippageAlertThreshold();
        if (actual.signum() >= 0) {
            return actual.compareTo(expected.abs().multiply(BigDecimal.ONE.subtract(threshold))) < 0;
        } else {
            return actual.abs().compareTo(expected.abs().multiply(BigDecimal.ONE.add(threshold))) > 0;
        }
    }

    private LegFillDto toLegFill(String orderId, String legTag,
                                  LegOrderRequest leg, OrderStatusResponse status) {
        BigDecimal avgPrice = status.data().averagePrice() != null
                ? status.data().averagePrice().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal slippage = leg.action() == LegAction.SELL
                ? leg.limitPrice().subtract(avgPrice)
                : avgPrice.subtract(leg.limitPrice());
        return new LegFillDto(orderId, legTag, leg.instrumentKey(), leg.optionType(),
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
     * Bypasses Upstox order placement entirely. Builds synthetic fills at each leg's limitPrice
     * (zero slippage), then runs the normal downstream path: slippage check → persistFills → ACTIVE.
     */
    private ExecuteTradeResponse executeSimulated(UUID tradeId, ExecuteTradeRequest request,
                                                  BigDecimal expectedNet) {
        log.warn("execution.fills.simulated",
                kv("tradeId", tradeId), kv("legCount", request.legs().size()),
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
                    OrderTagBuilder.entryTag(request.tradeId(), i),
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
