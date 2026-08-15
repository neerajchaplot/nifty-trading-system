package com.the3Cgrp.zupptrade.core.upstox.client;

import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.contract.UpstoxFuturesContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * Fetches NIFTY futures (FUT) contracts from Upstox via the Instrument Search API:
 * {@code GET /v2/instruments/search?query=NIFTY&segments=FO&instrument_types=FUT&exchanges=NSE}.
 *
 * Returns every matching FUT record (typically the 3 serial months, plus other NIFTY-family
 * underlyings the query matches). The caller filters to the exact underlying and picks the
 * current-month contract. Returns an empty list on any error — callers handle that gracefully.
 * Mirrors {@link UpstoxExpiryClient}'s thin-client style.
 */
public class UpstoxFuturesContractClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxFuturesContractClient.class);
    private static final ParameterizedTypeReference<UpstoxApiResponse<List<UpstoxFuturesContract>>>
            RESPONSE_TYPE = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public UpstoxFuturesContractClient(@Qualifier("upstoxRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /** @return NIFTY-family FUT contracts, or an empty list on any error. */
    public List<UpstoxFuturesContract> fetchNiftyFutures() {
        try {
            UpstoxApiResponse<List<UpstoxFuturesContract>> response = restClient.get()
                    .uri("/v2/instruments/search?query={q}&segments={s}&instrument_types={t}&exchanges={e}",
                            "NIFTY", "FO", "FUT", "NSE")
                    .retrieve()
                    .body(RESPONSE_TYPE);

            if (response == null || !response.isSuccess() || response.data() == null) {
                log.warn("upstox.futures.contract.empty status={}", response != null ? response.status() : "null");
                return Collections.emptyList();
            }
            log.info("upstox.futures.contract.fetched count={}", response.data().size());
            return response.data();
        } catch (Exception e) {
            // Transient/handled failure — degrade to empty (caller treats a missing token gracefully).
            // One-line warn, not a full stack, so the error-path test output stays clean.
            log.warn("upstox.futures.contract.error error={}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
