package com.the3Cgrp.zupptrade.core.expiry;

import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.contract.UpstoxOptionContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Fetches all Nifty option contract records from Upstox and extracts unique
 * expiry dates. The response contains every strike × expiry combination, so
 * we deduplicate and sort in-memory after the single API call.
 */
public class UpstoxExpiryClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxExpiryClient.class);
    private static final String INSTRUMENT_KEY = "NSE_INDEX|Nifty 50";
    private static final ParameterizedTypeReference<UpstoxApiResponse<List<UpstoxOptionContract>>>
            RESPONSE_TYPE = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public UpstoxExpiryClient(@Qualifier("upstoxRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Returns a sorted (ascending) list of unique expiry dates for Nifty options.
     * Returns an empty list on any error — callers must handle this gracefully.
     */
    public List<LocalDate> fetchAllExpiries() {
        try {
            UpstoxApiResponse<List<UpstoxOptionContract>> response = restClient.get()
                    .uri("/v2/option/contract?instrument_key={key}", INSTRUMENT_KEY)
                    .retrieve()
                    .body(RESPONSE_TYPE);

            if (response == null || !response.isSuccess() || response.data() == null) {
                log.warn("upstox.expiry.fetch.empty status={}", response != null ? response.status() : "null");
                return Collections.emptyList();
            }

            List<LocalDate> sorted = response.data().stream()
                    .map(UpstoxOptionContract::expiry)
                    .filter(d -> d != null)
                    .distinct()
                    .sorted()
                    .toList();

            log.info("upstox.expiry.fetched count={} first={} last={}",
                    sorted.size(),
                    sorted.isEmpty() ? null : sorted.get(0),
                    sorted.isEmpty() ? null : sorted.get(sorted.size() - 1));
            return sorted;

        } catch (Exception ex) {
            log.error("upstox.expiry.fetch.error", ex);
            return Collections.emptyList();
        }
    }
}
