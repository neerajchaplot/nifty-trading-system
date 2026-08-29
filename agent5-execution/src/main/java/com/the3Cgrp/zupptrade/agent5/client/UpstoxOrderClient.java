package com.the3Cgrp.zupptrade.agent5.client;

import com.the3Cgrp.zupptrade.agent5.client.request.MarginCheckRequest;
import com.the3Cgrp.zupptrade.agent5.client.request.PlaceOrderV3Request;
import com.the3Cgrp.zupptrade.agent5.client.response.FundsAndMarginResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.PlaceOrderV3Response;
import com.the3Cgrp.zupptrade.agent5.client.response.TaggedOrdersResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Thin HTTP client for all Upstox order and margin operations.
 *
 * Order placement / modify / cancel use the Upstox v3 order APIs (v2 order endpoints are not
 * served on the sandbox host, and v3 is the current standard). Order reads (status, by-tag) stay
 * on /v2/order/details — there is no v3 read endpoint.
 *
 * Uses THREE RestClient beans from core-module's UpstoxAutoConfiguration:
 *   upstoxRestClient          → api.upstox.com       (margin + funds — production token)
 *   upstoxOrderRestClient     → api-hft.upstox.com   (place/modify/cancel — prod token)
 *                            or api-sandbox.upstox.com (place/modify/cancel — sandbox token)
 *   upstoxOrderReadRestClient → api.upstox.com       (order status + history — prod token)
 *                            or api-sandbox.upstox.com (order status + history — sandbox token)
 *   Order reads are NOT served on the HFT placement host, hence the separate read client.
 *
 * Responsibilities: HTTP only — serialisation, retry on transient errors, logging.
 * Business logic (polling, rollback, slippage, reconcile) lives in TradeExecutionService.
 */
@Component
public class UpstoxOrderClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxOrderClient.class);

    private static final String PLACE_ORDER_URI   = "/v3/order/place";
    private static final String ORDER_STATUS_URI  = "/v2/order/details";   // single order by order_id — no v3 variant
    private static final String ORDER_HISTORY_URI = "/v2/order/history";   // by-tag lookup lives here, not /details
    private static final String MODIFY_ORDER_URI  = "/v3/order/modify";
    private static final String CANCEL_ORDER_URI = "/v3/order/cancel";
    private static final String MARGIN_CHECK_URI = "/v2/charges/margin";
    private static final String FUNDS_MARGIN_URI = "/v2/user/get-funds-and-margin";

    private final RestClient marketRestClient;     // margin + funds (api.upstox.com)
    private final RestClient orderRestClient;      // order place/modify/cancel (api-hft / api-sandbox)
    private final RestClient orderReadRestClient;  // order reads: status + history (api.upstox.com / api-sandbox)
    private final long       retryDelayMs;         // fixed backoff between retries of idempotent calls

    public UpstoxOrderClient(
            @Qualifier("upstoxRestClient")          RestClient marketRestClient,
            @Qualifier("upstoxOrderRestClient")     RestClient orderRestClient,
            @Qualifier("upstoxOrderReadRestClient") RestClient orderReadRestClient,
            @Value("${upstox.api.retry-delay-ms:2000}") long retryDelayMs) {
        this.marketRestClient    = marketRestClient;
        this.orderRestClient     = orderRestClient;
        this.orderReadRestClient = orderReadRestClient;
        this.retryDelayMs        = retryDelayMs;
    }

    // ── Per-user token (Phase 4) ────────────────────────────────────────────

    /**
     * A view of this client bound to one Upstox token — created once per execution with the trade
     * OWNER's token so every call in that flow acts on the right account. When {@code bearerToken}
     * is null the calls fall through to the system/order token via the RestClient interceptors
     * (unchanged behaviour). Since the token is baked into the session, no call site can forget it.
     */
    public OrderSession session(String bearerToken) {
        return new OrderSession(bearerToken);
    }

    /** Sets the per-call Bearer only when a token is supplied; otherwise the interceptor decides. */
    private static void applyBearer(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
        }
    }

    public final class OrderSession {
        private final String token;
        private OrderSession(String token) { this.token = token; }

        // token == null → use the base method (system token via interceptor); otherwise pass the
        // per-user token. Both are behaviourally identical (applyBearer no-ops on null), but routing
        // the no-token case through the base method keeps existing single-token callers/tests intact.
        public MarginCheckResponse checkMargin(MarginCheckRequest r) {
            return token == null ? UpstoxOrderClient.this.checkMargin(r) : UpstoxOrderClient.this.checkMargin(r, token);
        }
        public FundsAndMarginResponse getAvailableFunds() {
            return token == null ? UpstoxOrderClient.this.getAvailableFunds() : UpstoxOrderClient.this.getAvailableFunds(token);
        }
        public PlaceOrderV3Response placeOrder(PlaceOrderV3Request r) {
            return token == null ? UpstoxOrderClient.this.placeOrder(r) : UpstoxOrderClient.this.placeOrder(r, token);
        }
        public OrderStatusResponse getOrderStatus(String orderId) {
            return token == null ? UpstoxOrderClient.this.getOrderStatus(orderId) : UpstoxOrderClient.this.getOrderStatus(orderId, token);
        }
        // TEMP DIAGNOSTIC — order-domain read (no placement). Remove after Upstox diagnosis.
        public String getOrderBook() {
            return token == null ? UpstoxOrderClient.this.getOrderBook(null) : UpstoxOrderClient.this.getOrderBook(token);
        }
        public TaggedOrdersResponse getOrderDetailsByTag(String tag) {
            return token == null ? UpstoxOrderClient.this.getOrderDetailsByTag(tag) : UpstoxOrderClient.this.getOrderDetailsByTag(tag, token);
        }
        public void modifyToMarket(String orderId, int qty) {
            if (token == null) UpstoxOrderClient.this.modifyToMarket(orderId, qty);
            else UpstoxOrderClient.this.modifyToMarket(orderId, qty, token);
        }
        public void cancelOrder(String orderId) {
            if (token == null) UpstoxOrderClient.this.cancelOrder(orderId);
            else UpstoxOrderClient.this.cancelOrder(orderId, token);
        }
    }

    // ── Margin check (api.upstox.com) ──────────────────────────────────────

    public MarginCheckResponse checkMargin(MarginCheckRequest request) { return checkMargin(request, null); }

    public MarginCheckResponse checkMargin(MarginCheckRequest request, String bearerToken) {
        log.info("upstox.margin.check", kv("legCount", request.instruments().size()));

        MarginCheckResponse response = withRetry("checkMargin",
                () -> marketRestClient.post()
                        .uri(MARGIN_CHECK_URI)
                        .headers(h -> applyBearer(h, bearerToken))
                        .body(request)
                        .retrieve()
                        .body(MarginCheckResponse.class));

        if (response == null || !response.isApiSuccess()) {
            throw new UpstoxOrderException("Margin check returned null or error response");
        }

        log.info("upstox.margin.result",
                kv("requiredMargin", response.data().requiredMargin()),
                kv("finalMargin", response.data().finalMargin()));

        return response;
    }

    // ── Available funds check (api.upstox.com) ─────────────────────────────

    public FundsAndMarginResponse getAvailableFunds() { return getAvailableFunds(null); }

    public FundsAndMarginResponse getAvailableFunds(String bearerToken) {
        log.info("upstox.funds.check");

        FundsAndMarginResponse response = withRetry("getAvailableFunds",
                () -> marketRestClient.get()
                        .uri(FUNDS_MARGIN_URI + "?segment=SEC")
                        .headers(h -> applyBearer(h, bearerToken))
                        .retrieve()
                        .body(FundsAndMarginResponse.class));

        if (response == null || !response.isApiSuccess()) {
            throw new UpstoxOrderException("Fund-and-margin check returned null or error response");
        }

        log.info("upstox.funds.result", kv("availableMargin", response.availableMargin()));
        return response;
    }

    // ── Place single order — V3 (upstoxOrderRestClient) ─────────────────────

    public PlaceOrderV3Response placeOrder(PlaceOrderV3Request request) { return placeOrder(request, null); }

    /**
     * Places ONE order (one leg). We never slice, so a successful response carries exactly one
     * order_id. NEVER retried: a 5xx/timeout does not prove the order failed to reach the exchange,
     * so a retry could duplicate it — the caller reconciles by tag instead (an ambiguous failure
     * throws UpstoxOrderException with ambiguous=true).
     */
    public PlaceOrderV3Response placeOrder(PlaceOrderV3Request request, String bearerToken) {
        log.info("upstox.order.place",
                kv("instrument", request.instrumentToken()), kv("txn", request.transactionType()),
                kv("qty", request.quantity()), kv("tag", request.tag()));

        PlaceOrderV3Response response = withRetry("placeOrder", 1,
                () -> orderRestClient.post()
                        .uri(PLACE_ORDER_URI)
                        .headers(h -> applyBearer(h, bearerToken))
                        .body(request)
                        .retrieve()
                        .body(PlaceOrderV3Response.class));

        if (response == null || !response.isApiSuccess()) {
            throw new UpstoxOrderException("Order placement returned null or error response");
        }

        log.info("upstox.order.place.result orderIds={}", response.orderIds());
        return response;
    }

    // ── Order status by order_id — v2 read (upstoxOrderRestClient) ───────────

    public OrderStatusResponse getOrderStatus(String orderId) { return getOrderStatus(orderId, null); }

    public OrderStatusResponse getOrderStatus(String orderId, String bearerToken) {
        log.debug("upstox.order.status", kv("orderId", orderId));

        OrderStatusResponse response = withRetry("getOrderStatus",
                () -> orderReadRestClient.get()
                        .uri(ORDER_STATUS_URI + "?order_id={id}", orderId)
                        .headers(h -> applyBearer(h, bearerToken))
                        .retrieve()
                        .body(OrderStatusResponse.class));

        if (response == null) {
            throw new UpstoxOrderException("Order status returned null for orderId=" + orderId);
        }
        return response;
    }

    // ── TEMP DIAGNOSTIC: order-book read — v2 read host (upstoxOrderReadRestClient) ──
    // Proves whether the trade owner's token (same one that 401s on /v3/order/place) can read the
    // order domain at all. GET /v2/order/retrieve-all places NOTHING. Remove after Upstox diagnosis.
    public String getOrderBook(String bearerToken) {
        log.info("upstox.diag.orderbook.read");
        return withRetry("getOrderBook",
                () -> orderReadRestClient.get()
                        .uri("/v2/order/retrieve-all")
                        .headers(h -> applyBearer(h, bearerToken))
                        .retrieve()
                        .body(String.class));
    }

    // ── Order lookup by tag — v2 read (upstoxOrderRestClient) ────────────────

    public TaggedOrdersResponse getOrderDetailsByTag(String tag) { return getOrderDetailsByTag(tag, null); }

    /**
     * Fetches orders placed under one (unique per-leg) tag via the Order History API — by-tag
     * lookup lives on /v2/order/history, NOT /v2/order/details (which keys on order_id only).
     * Idempotent GET — safe to retry. Used by the ambiguous-placement-failure reconciler to
     * discover what actually landed. History may return multiple rows per order (state
     * progression); the reconciler dedups by order_id and re-reads each by order_id.
     */
    public TaggedOrdersResponse getOrderDetailsByTag(String tag, String bearerToken) {
        log.info("upstox.order.by_tag", kv("tag", tag));

        TaggedOrdersResponse response = withRetry("getOrderDetailsByTag",
                () -> orderReadRestClient.get()
                        .uri(ORDER_HISTORY_URI + "?tag={tag}", tag)
                        .headers(h -> applyBearer(h, bearerToken))
                        .retrieve()
                        .body(TaggedOrdersResponse.class));

        if (response == null) {
            throw new UpstoxOrderException("Order-by-tag returned null for tag=" + tag);
        }
        return response;
    }

    // ── Modify LIMIT → MARKET — V3 (upstoxOrderRestClient) ──────────────────

    public void modifyToMarket(String orderId, int quantity) { modifyToMarket(orderId, quantity, null); }

    public void modifyToMarket(String orderId, int quantity, String bearerToken) {
        log.info("upstox.order.modify.market", kv("orderId", orderId), kv("quantity", quantity));

        withRetry("modifyToMarket",
                () -> orderRestClient.put()
                        .uri(MODIFY_ORDER_URI)
                        .headers(h -> applyBearer(h, bearerToken))
                        .body(new ModifyV3Request(orderId, "MARKET", "DAY", 0.0, quantity, 0, 0.0))
                        .retrieve()
                        .body(String.class));
    }

    // ── Cancel order — V3 (upstoxOrderRestClient) ───────────────────────────

    public void cancelOrder(String orderId) { cancelOrder(orderId, null); }

    public void cancelOrder(String orderId, String bearerToken) {
        log.info("upstox.order.cancel", kv("orderId", orderId));

        withRetry("cancelOrder",
                () -> orderRestClient.delete()
                        .uri(CANCEL_ORDER_URI + "?order_id={id}", orderId)
                        .headers(h -> applyBearer(h, bearerToken))
                        .retrieve()
                        .body(String.class));
    }

    // ── Inline V3 modify request ─────────────────────────────────────────────

    private record ModifyV3Request(
            @com.fasterxml.jackson.annotation.JsonProperty("order_id")           String orderId,
            @com.fasterxml.jackson.annotation.JsonProperty("order_type")         String orderType,
            @com.fasterxml.jackson.annotation.JsonProperty("validity")           String validity,
            @com.fasterxml.jackson.annotation.JsonProperty("price")              double price,
            @com.fasterxml.jackson.annotation.JsonProperty("quantity")           int quantity,
            @com.fasterxml.jackson.annotation.JsonProperty("disclosed_quantity") int disclosedQuantity,
            @com.fasterxml.jackson.annotation.JsonProperty("trigger_price")      double triggerPrice
    ) {}

    // ── Retry wrapper ────────────────────────────────────────────────────────

    private <T> T withRetry(String operation, java.util.concurrent.Callable<T> call) {
        return withRetry(operation, 3, call);
    }

    /**
     * Retry wrapper. {@code maxAttempts} is the total number of attempts (1 = no retry).
     * Use maxAttempts = 1 for non-idempotent operations (order placement) where a retry could
     * duplicate a side effect; the default 3 is for idempotent reads (status, tag, margin) + cancel.
     *
     * Retryable failures: 429 (rate limit), 5xx, network/timeout — separated by a fixed
     * {@code retryDelayMs} backoff so a transient blip is ridden out rather than escalated.
     * Non-transient 4xx (400/401/403/404 …) throw immediately with ambiguous=false (deterministic —
     * nothing was placed). A failure after exhausting retries throws ambiguous=true (the outcome is
     * unknown — a placement may or may not have reached the exchange).
     */
    private <T> T withRetry(String operation, int maxAttempts, java.util.concurrent.Callable<T> call) {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.call();
            } catch (RestClientResponseException e) {
                boolean rateLimited = e.getStatusCode().value() == 429;
                if (e.getStatusCode().is4xxClientError() && !rateLimited) {
                    // Non-transient client error — deterministic, nothing placed.
                    // TEMP DIAGNOSTIC: log selected response headers on 4xx. An empty-body 401 gives
                    // no clue on its own; the gateway headers (WWW-Authenticate / Server / x-*) tell an
                    // edge/IP reject apart from an account-entitlement reject. Remove once diagnosed.
                    org.springframework.http.HttpHeaders rh = e.getResponseHeaders();
                    log.error("upstox.client.error",
                            kv("operation", operation),
                            kv("status", e.getStatusCode().value()),
                            kv("wwwAuthenticate", rh == null ? null : rh.getFirst("WWW-Authenticate")),
                            kv("server", rh == null ? null : rh.getFirst("Server")),
                            kv("allHeaders", rh == null ? null : rh.toString()),
                            kv("body", e.getResponseBodyAsString()));
                    throw new UpstoxOrderException(
                            operation + " failed with " + e.getStatusCode() + ": " + e.getResponseBodyAsString(),
                            e, false);
                }
                last = e;   // 429 or 5xx — retryable
                log.warn("upstox.retryable.error",
                        kv("operation", operation), kv("attempt", attempt), kv("status", e.getStatusCode().value()));
            } catch (Exception e) {
                last = e;   // network / timeout — retryable
                log.warn("upstox.error.retry",
                        kv("operation", operation), kv("attempt", attempt), kv("error", e.getMessage()));
            }
            if (attempt < maxAttempts) {
                sleep(retryDelayMs);
            }
        }
        // Exhausted retries — outcome unknown (ambiguous).
        throw new UpstoxOrderException(operation + " failed after " + maxAttempts + " attempts", last, true);
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Exception ────────────────────────────────────────────────────────────

    /**
     * {@code ambiguous} = we cannot be sure whether the request took effect on the exchange
     * (5xx/timeout/network after retries). false = a deterministic 4xx rejection (nothing placed).
     * Order placement callers use this to decide reconcile-by-tag (ambiguous) vs clean reject.
     */
    public static class UpstoxOrderException extends RuntimeException {
        private final boolean ambiguous;

        public UpstoxOrderException(String msg) { this(msg, null, true); }
        public UpstoxOrderException(String msg, Throwable cause) { this(msg, cause, true); }
        public UpstoxOrderException(String msg, Throwable cause, boolean ambiguous) {
            super(msg, cause);
            this.ambiguous = ambiguous;
        }

        public boolean isAmbiguous() { return ambiguous; }
    }
}
