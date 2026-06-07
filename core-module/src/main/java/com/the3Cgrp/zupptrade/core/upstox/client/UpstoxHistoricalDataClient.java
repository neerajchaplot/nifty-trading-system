package com.the3Cgrp.zupptrade.core.upstox.client;

import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.candle.UpstoxCandleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * GET /v2/historical-candle/{instrument_key}/day/{to_date}/{from_date}
 *
 * Returns daily OHLCV candles for the given instrument.
 * Instrument key for Nifty 50 index: NSE_INDEX|Nifty 50 (URL-encoded automatically).
 *
 * Response candle format: [timestamp, open, high, low, close, volume, oi]
 */
public class UpstoxHistoricalDataClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxHistoricalDataClient.class);
    private static final String NIFTY_50_KEY = "NSE_INDEX|Nifty 50";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RestClient upstoxRestClient;

    public UpstoxHistoricalDataClient(RestClient upstoxRestClient) {
        this.upstoxRestClient = upstoxRestClient;
    }

    /**
     * Fetches Nifty 50 daily candles for the last {@code days} calendar days
     * (adds a 30-day buffer to account for weekends and holidays).
     */
    public List<UpstoxCandle> fetchNiftyDailyCandles(int days) {
        return fetchDailyCandles(NIFTY_50_KEY, LocalDate.now().minusDays(days + 30L), LocalDate.now());
    }

    public List<UpstoxCandle> fetchDailyCandles(String instrumentKey, LocalDate from, LocalDate to) {
        try {
            // Pass raw instrumentKey — RestClient URI template encoding handles | and spaces.
            // Manual pre-encoding before a URI template variable causes double-encoding (%7C → %257C).
            UpstoxApiResponse<UpstoxCandleResponse> response = upstoxRestClient.get()
                    .uri("/v2/historical-candle/{key}/day/{to}/{from}",
                            instrumentKey, to.format(DATE_FMT), from.format(DATE_FMT))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.isSuccess() || response.data() == null
                    || response.data().candles() == null) {
                log.warn("upstox.historical.empty_response instrument={} status={}",
                        instrumentKey, response != null ? response.status() : "null");
                return List.of();
            }
            return response.data().candles().stream()
                    .map(this::parseCandle)
                    .toList();
        } catch (Exception e) {
            log.warn("upstox.historical.error instrument={} error={}", instrumentKey, e.getMessage(), e);
            return List.of();
        }
    }

    private UpstoxCandle parseCandle(List<Object> raw) {
        // [timestamp, open, high, low, close, volume, oi]
        String timestampStr = (String) raw.get(0);
        LocalDate date = LocalDate.parse(timestampStr.substring(0, 10));
        return new UpstoxCandle(
                date,
                toBigDecimal(raw.get(1)),
                toBigDecimal(raw.get(2)),
                toBigDecimal(raw.get(3)),
                toBigDecimal(raw.get(4)),
                ((Number) raw.get(5)).longValue()
        );
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return new BigDecimal(value.toString());
    }

    /** Parsed candle from Upstox historical API. */
    public record UpstoxCandle(
            LocalDate date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume
    ) {}
}
