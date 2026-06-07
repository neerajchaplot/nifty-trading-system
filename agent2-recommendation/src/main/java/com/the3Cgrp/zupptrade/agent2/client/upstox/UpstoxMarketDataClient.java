package com.the3Cgrp.zupptrade.agent2.client.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.the3Cgrp.zupptrade.agent2.client.MarketDataClient;
import com.the3Cgrp.zupptrade.agent2.client.model.MarketSnapshot;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.exception.MarketDataUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static net.logstash.logback.argument.StructuredArguments.kv;

// Verify Upstox quote endpoint and VIX instrument key against v2 API docs before going live
@Component
public class UpstoxMarketDataClient implements MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxMarketDataClient.class);
    private static final String INDIA_VIX_INSTRUMENT_KEY = "NSE_INDEX|India VIX";

    private final RestClient restClient;
    private final TradingConfig config;

    public UpstoxMarketDataClient(RestClient upstoxRestClient, TradingConfig config) {
        this.restClient = upstoxRestClient;
        this.config = config;
    }

    @Override
    public MarketSnapshot fetchSnapshot() {
        int maxRetries = config.getUpstox().getRetryMax();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String instrumentKeys = config.getUpstox().getNiftyInstrumentKey()
                        + "," + INDIA_VIX_INSTRUMENT_KEY;

                UpstoxQuoteResponse response = restClient.get()
                        .uri("/v2/market-quote/ltp?instrument_key={keys}", instrumentKeys)
                        .retrieve()
                        .body(UpstoxQuoteResponse.class);

                MarketSnapshot snapshot = mapToSnapshot(response);
                log.debug("market.snapshot.fetched",
                        kv("spot", snapshot.spot()),
                        kv("vix", snapshot.vix()),
                        kv("attempt", attempt));
                return snapshot;

            } catch (RestClientException ex) {
                lastException = ex;
                log.warn("market.snapshot.fetch.retry",
                        kv("attempt", attempt),
                        kv("maxRetries", maxRetries),
                        kv("error", ex.getMessage()));
            }
        }

        throw new MarketDataUnavailableException(
                "Failed to fetch market snapshot after " + maxRetries + " attempts", lastException);
    }

    private MarketSnapshot mapToSnapshot(UpstoxQuoteResponse response) {
        if (response == null || response.data() == null) {
            throw new MarketDataUnavailableException("Empty market quote response from data provider");
        }

        String niftyKey = config.getUpstox().getNiftyInstrumentKey();
        UpstoxQuoteResponse.QuoteData nifty = response.data().get(niftyKey);
        UpstoxQuoteResponse.QuoteData vixData = response.data().get(INDIA_VIX_INSTRUMENT_KEY);

        if (nifty == null) {
            throw new MarketDataUnavailableException("Nifty spot price missing from market quote response");
        }

        BigDecimal spot = BigDecimal.valueOf(nifty.lastPrice());
        BigDecimal vix = vixData != null ? BigDecimal.valueOf(vixData.lastPrice()) : BigDecimal.ZERO;

        return new MarketSnapshot(spot, vix, LocalDateTime.now());
    }

    // Inline response DTO — only used by this client
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UpstoxQuoteResponse(
            @JsonProperty("status") String status,
            @JsonProperty("data") java.util.Map<String, QuoteData> data
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record QuoteData(
                @JsonProperty("last_price") double lastPrice
        ) {}
    }
}
