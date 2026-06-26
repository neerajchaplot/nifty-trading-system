package com.the3Cgrp.zupptrade.agent5.client;

import com.the3Cgrp.zupptrade.agent5.client.request.MarginCheckRequest;
import com.the3Cgrp.zupptrade.agent5.client.request.MultiOrderRequest;
import com.the3Cgrp.zupptrade.agent5.client.response.FundsAndMarginResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MarginCheckResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.MultiOrderResponse;
import com.the3Cgrp.zupptrade.agent5.client.response.OrderStatusResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Thin HTTP client for all Upstox order and margin operations.
 *
 * Uses TWO RestClient beans from core-module's UpstoxAutoConfiguration:
 *   upstoxRestClient      → api.upstox.com         (margin check — production token)
 *   upstoxOrderRestClient → api-hft.upstox.com      (orders — production token)
 *                        or api-sandbox.upstox.com   (orders — sandbox token, sandbox profile)
 *
 * Responsibilities: HTTP only — serialisation, retry on 5xx, logging.
 * Business logic (polling, rollback, slippage) lives in TradeExecutionService.
 */
@Component
public class UpstoxOrderClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxOrderClient.class);

    private static final String MULTI_PLACE_URI  = "/v2/order/multi/place";
    private static final String ORDER_STATUS_URI = "/v2/order/details";
    private static final String MODIFY_ORDER_URI = "/v2/order/modify";
    private static final String CANCEL_ORDER_URI = "/v2/order/cancel";
    private static final String MARGIN_CHECK_URI = "/v2/charges/margin";
    private static final String FUNDS_MARGIN_URI  = "/v2/user/fund-and-margin";

    private final RestClient marketRestClient;  // for margin check
    private final RestClient orderRestClient;   // for order placement / modify / cancel

    public UpstoxOrderClient(
            @Qualifier("upstoxRestClient")      RestClient marketRestClient,
            @Qualifier("upstoxOrderRestClient") RestClient orderRestClient) {
        this.marketRestClient = marketRestClient;
        this.orderRestClient  = orderRestClient;
    }

    // ── Margin check (api.upstox.com) ──────────────────────────────────────

    public MarginCheckResponse checkMargin(MarginCheckRequest request) {
        log.info("upstox.margin.check", kv("legCount", request.instruments().size()));

        MarginCheckResponse response = withRetry("checkMargin",
                () -> marketRestClient.post()
                        .uri(MARGIN_CHECK_URI)
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

    public FundsAndMarginResponse getAvailableFunds() {
        log.info("upstox.funds.check");

        FundsAndMarginResponse response = withRetry("getAvailableFunds",
                () -> marketRestClient.get()
                        .uri(FUNDS_MARGIN_URI + "?segment=SEC")
                        .retrieve()
                        .body(FundsAndMarginResponse.class));

        if (response == null || !response.isApiSuccess()) {
            throw new UpstoxOrderException("Fund-and-margin check returned null or error response");
        }

        log.info("upstox.funds.result", kv("availableMargin", response.availableMargin()));
        return response;
    }

    // ── Multi-order placement (upstoxOrderRestClient) ───────────────────────

    public MultiOrderResponse placeMultiOrder(MultiOrderRequest request) {
        log.info("upstox.multi.place", kv("legCount", request.orders().size()));

        MultiOrderResponse response = withRetry("placeMultiOrder",
                () -> orderRestClient.post()
                        .uri(MULTI_PLACE_URI)
                        .body(request)
                        .retrieve()
                        .body(MultiOrderResponse.class));

        if (response == null || !response.isApiSuccess()) {
            throw new UpstoxOrderException("Multi-order placement returned null or error response");
        }

        log.info("upstox.multi.place.result",
                kv("total", response.summary() != null ? response.summary().total() : 0),
                kv("success", response.summary() != null ? response.summary().success() : 0),
                kv("error", response.summary() != null ? response.summary().error() : 0),
                kv("payloadError", response.summary() != null ? response.summary().payloadError() : 0));

        return response;
    }

    // ── Order status (upstoxOrderRestClient) ────────────────────────────────

    public OrderStatusResponse getOrderStatus(String orderId) {
        log.debug("upstox.order.status", kv("orderId", orderId));

        OrderStatusResponse response = withRetry("getOrderStatus",
                () -> orderRestClient.get()
                        .uri(ORDER_STATUS_URI + "?order_id={id}", orderId)
                        .retrieve()
                        .body(OrderStatusResponse.class));

        if (response == null) {
            throw new UpstoxOrderException("Order status returned null for orderId=" + orderId);
        }
        return response;
    }

    // ── Modify LIMIT → MARKET (upstoxOrderRestClient) ───────────────────────

    public void modifyToMarket(String orderId, int quantity) {
        log.info("upstox.order.modify.market", kv("orderId", orderId), kv("quantity", quantity));

        withRetry("modifyToMarket",
                () -> orderRestClient.put()
                        .uri(MODIFY_ORDER_URI)
                        .body(new ModifyRequest(orderId, "MARKET", "DAY", 0.0, quantity, 0, 0.0))
                        .retrieve()
                        .body(String.class));
    }

    // ── Cancel order (upstoxOrderRestClient) ────────────────────────────────

    public void cancelOrder(String orderId) {
        log.info("upstox.order.cancel", kv("orderId", orderId));

        withRetry("cancelOrder",
                () -> orderRestClient.delete()
                        .uri(CANCEL_ORDER_URI + "?order_id={id}", orderId)
                        .retrieve()
                        .body(String.class));
    }

    // ── Inline modify request ────────────────────────────────────────────────

    private record ModifyRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("order_id")        String orderId,
            @com.fasterxml.jackson.annotation.JsonProperty("order_type")       String orderType,
            @com.fasterxml.jackson.annotation.JsonProperty("validity")         String validity,
            @com.fasterxml.jackson.annotation.JsonProperty("price")            double price,
            @com.fasterxml.jackson.annotation.JsonProperty("quantity")         int quantity,
            @com.fasterxml.jackson.annotation.JsonProperty("disclosed_quantity") int disclosedQuantity,
            @com.fasterxml.jackson.annotation.JsonProperty("trigger_price")    double triggerPrice
    ) {}

    // ── Retry wrapper ────────────────────────────────────────────────────────

    private <T> T withRetry(String operation, java.util.concurrent.Callable<T> call) {
        int maxRetries = 3;
        Exception last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return call.call();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    log.error("upstox.client.error",
                            kv("operation", operation),
                            kv("status", e.getStatusCode().value()),
                            kv("body", e.getResponseBodyAsString()));
                    throw new UpstoxOrderException(
                            operation + " failed with " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
                }
                last = e;
                log.warn("upstox.server.error.retry",
                        kv("operation", operation), kv("attempt", attempt), kv("status", e.getStatusCode().value()));
            } catch (Exception e) {
                last = e;
                log.warn("upstox.error.retry",
                        kv("operation", operation), kv("attempt", attempt), kv("error", e.getMessage()));
            }
        }
        throw new UpstoxOrderException(operation + " failed after " + maxRetries + " attempts", last);
    }

    // ── Exception ────────────────────────────────────────────────────────────

    public static class UpstoxOrderException extends RuntimeException {
        public UpstoxOrderException(String msg) { super(msg); }
        public UpstoxOrderException(String msg, Throwable cause) { super(msg, cause); }
    }
}
