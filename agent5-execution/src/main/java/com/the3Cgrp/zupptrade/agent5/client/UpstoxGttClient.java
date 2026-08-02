package com.the3Cgrp.zupptrade.agent5.client;

import com.the3Cgrp.zupptrade.agent5.client.request.PlaceGttRequest;
import com.the3Cgrp.zupptrade.agent5.client.response.PlaceGttResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Thin HTTP client for Upstox v3 GTT placement (futures).
 *
 * Per the Upstox v3 GTT spec, {@code /v3/order/gtt/place} is served on the main API host
 * (api.upstox.com) — the {@code upstoxRestClient} bean — not the HFT order host. HTTP only;
 * business logic (validation, alerts, status updates) lives in FuturesExecutionService.
 */
@Component
public class UpstoxGttClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxGttClient.class);
    private static final String GTT_PLACE_URI = "/v3/order/gtt/place";

    private final RestClient marketRestClient;

    public UpstoxGttClient(@Qualifier("upstoxRestClient") RestClient marketRestClient) {
        this.marketRestClient = marketRestClient;
    }

    /** Places the multi-leg GTT. Throws on transport/HTTP error — the caller alerts + fails the plan. */
    public PlaceGttResponse placeGtt(PlaceGttRequest request) {
        log.info("upstox.gtt.place", kv("tag", request.tag()),
                kv("txn", request.transactionType()), kv("qty", request.quantity()));
        return marketRestClient.post()
                .uri(GTT_PLACE_URI)
                .body(request)
                .retrieve()
                .body(PlaceGttResponse.class);
    }
}
