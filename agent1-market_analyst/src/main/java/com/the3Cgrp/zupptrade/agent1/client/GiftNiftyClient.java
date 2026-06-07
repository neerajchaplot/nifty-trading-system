package com.the3Cgrp.zupptrade.agent1.client;

import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.quote.UpstoxLtpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Fetches live GIFT Nifty (SGX Nifty) value from Upstox v3 Market Quote API.
 *
 * Endpoint : GET /v3/market-quote/ltp?instrument_key=GLOBAL_INDEX|SGX NIFTY
 * Exchange : GLOBAL_INDEX  (not NSE_INDEX — this is a global index feed)
 * Symbol   : SGX NIFTY     (original name; "GIFT Nifty" is the marketing rename)
 *
 * Sample response:
 *   { "status":"success", "data": {
 *       "GLOBAL_INDEX:SGX NIFTY": { "last_price":23643.5, "cp":23658.5, ... }
 *   }}
 *
 * Note: Upstox returns the map key with ':' separator in the response
 *       even though the request uses '|'. Same pattern as VIX — handled with fallback.
 *
 * Used by VolatilityMacroTierScorer (Tier 3):
 *   giftNiftyPremium = giftNiftyLtp - niftyPrevClose
 *   Threshold: premium > +50 pts = BULLISH (+1), < -50 pts = BEARISH (-1)
 */
@Component
public class GiftNiftyClient {

    private static final Logger log = LoggerFactory.getLogger(GiftNiftyClient.class);

    private static final String INSTRUMENT_KEY = "GLOBAL_INDEX|SGX NIFTY";
    private static final String V3_LTP_PATH    = "/v3/market-quote/ltp";

    private final RestClient upstoxRestClient;

    public GiftNiftyClient(@Qualifier("upstoxRestClient") RestClient upstoxRestClient) {
        this.upstoxRestClient = upstoxRestClient;
    }

    /**
     * Returns the current GIFT Nifty last traded price.
     * Returns null on any error (token expired, market closed, network issue) —
     * caller treats null as score 0 and logs to data_gaps.
     */
    public BigDecimal fetchLtp() {
        try {
            UpstoxApiResponse<Map<String, UpstoxLtpData>> response = upstoxRestClient.get()
                    .uri(u -> u.path(V3_LTP_PATH)
                            .queryParam("instrument_key", INSTRUMENT_KEY)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.isSuccess() || response.data() == null
                    || response.data().isEmpty()) {
                log.warn("upstox.gift-nifty.empty — no data returned (market closed or feed unavailable)");
                return null;
            }

            // Upstox returns map key with ':' but request uses '|' — try both
            UpstoxLtpData entry = response.data().get(INSTRUMENT_KEY);
            if (entry == null) {
                entry = response.data().get(INSTRUMENT_KEY.replace("|", ":"));
            }
            if (entry == null) {
                log.warn("upstox.gift-nifty.key_not_found keys_returned={}", response.data().keySet());
                return null;
            }

            log.info("upstox.gift-nifty.fetched ltp={}", entry.lastPrice());
            return entry.lastPrice();

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("upstox.token.expired instrument={} — re-run upstox-auth to refresh.", INSTRUMENT_KEY);
            return null;
        } catch (Exception e) {
            log.warn("upstox.gift-nifty.error instrument={} error={}", INSTRUMENT_KEY, e.getMessage());
            return null;
        }
    }
}
