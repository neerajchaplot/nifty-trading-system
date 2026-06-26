package com.the3Cgrp.zupptrade.core.upstox.client;

import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.quote.UpstoxLtpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * GET /v2/market-quote/ltp — fetches last traded price for any instrument.
 * Used by UpstoxOptionChainClient to fetch current India VIX level.
 *
 * Instrument keys:
 *   India VIX : NSE_INDEX|India VIX
 *   Nifty 50  : NSE_INDEX|Nifty 50
 */
public class UpstoxMarketQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxMarketQuoteClient.class);
    private static final String VIX_INSTRUMENT_KEY = "NSE_INDEX|India VIX";

    private final RestClient upstoxRestClient;

    public UpstoxMarketQuoteClient(RestClient upstoxRestClient) {
        this.upstoxRestClient = upstoxRestClient;
    }

    /** Returns current India VIX level, or null on any error. */
    public BigDecimal fetchVix() {
        return fetchLtp(VIX_INSTRUMENT_KEY);
    }

    public BigDecimal fetchLtp(String instrumentKey) {
        try {
            UpstoxApiResponse<Map<String, UpstoxLtpData>> response = upstoxRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/market-quote/ltp")
                            .queryParam("instrument_key", instrumentKey)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.isSuccess() || response.data() == null
                    || response.data().isEmpty()) {
                // Normal on weekends/holidays — live LTP not served outside market hours
                log.debug("upstox.ltp.unavailable instrument={} status={} reason=market_closed_or_empty_response",
                        instrumentKey, response != null ? response.status() : "null");
                return null;
            }

            // Upstox returns the map key with ':' as separator (e.g. "NSE_INDEX:India VIX")
            // but we send the request with '|' (e.g. "NSE_INDEX|India VIX").
            // Try the original key first, then fall back to the colon variant.
            UpstoxLtpData entry = response.data().get(instrumentKey);
            if (entry == null) {
                entry = response.data().get(instrumentKey.replace("|", ":"));
            }
            if (entry == null) {
                log.warn("upstox.ltp.key_not_found instrument={} keys_returned={}",
                        instrumentKey, response.data().keySet());
                return null;
            }
            return entry.lastPrice();

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("upstox.token.expired instrument={} — access token is invalid or expired. " +
                      "Paste a fresh token into application-local.yml and restart.", instrumentKey);
            return null;
        } catch (Exception e) {
            log.warn("upstox.ltp.error instrument={} error={}", instrumentKey, e.getMessage());
            return null;
        }
    }
}
