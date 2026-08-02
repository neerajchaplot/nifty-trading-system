package com.the3Cgrp.zupptrade.core.upstox.client;

import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.candle.UpstoxCandleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * GET /v3/historical-candle/intraday/{instrument_key}/minutes/{interval}
 *
 * Returns today's intraday OHLC candles at the requested minute interval (e.g. 5).
 * Candle array format: [timestamp, open, high, low, close, volume, oi] — timestamp is an
 * ISO-8601 string with offset (e.g. "2026-08-03T09:20:00+05:30").
 *
 * Used by Agent 3's futures entry state machine to detect 5-min candle closes beyond a trigger.
 */
public class UpstoxIntradayCandleClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxIntradayCandleClient.class);
    private static final String NIFTY_50_KEY = "NSE_INDEX|Nifty 50";

    private final RestClient upstoxRestClient;

    public UpstoxIntradayCandleClient(RestClient upstoxRestClient) {
        this.upstoxRestClient = upstoxRestClient;
    }

    /** Today's Nifty 50 index candles at the given minute interval, oldest-first. */
    public List<IntradayCandle> fetchNiftyIntradayCandles(int intervalMinutes) {
        return fetchIntradayCandles(NIFTY_50_KEY, intervalMinutes);
    }

    public List<IntradayCandle> fetchIntradayCandles(String instrumentKey, int intervalMinutes) {
        try {
            // Raw instrumentKey — RestClient URI-template encoding handles | and spaces
            // (manual pre-encoding double-encodes %7C → %257C).
            UpstoxApiResponse<UpstoxCandleResponse> response = upstoxRestClient.get()
                    .uri("/v3/historical-candle/intraday/{key}/minutes/{interval}",
                            instrumentKey, intervalMinutes)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.isSuccess() || response.data() == null
                    || response.data().candles() == null) {
                log.warn("upstox.intraday.empty_response instrument={} interval={} status={}",
                        instrumentKey, intervalMinutes, response != null ? response.status() : "null");
                return List.of();
            }
            // Upstox returns newest-first; sort oldest-first so the state machine walks the day in order.
            return response.data().candles().stream()
                    .map(this::parseCandle)
                    .sorted(Comparator.comparing(IntradayCandle::time))
                    .toList();
        } catch (Exception e) {
            log.warn("upstox.intraday.error instrument={} interval={} error={}",
                    instrumentKey, intervalMinutes, e.getMessage(), e);
            return List.of();
        }
    }

    private IntradayCandle parseCandle(List<Object> raw) {
        // [timestamp, open, high, low, close, volume, oi]
        return new IntradayCandle(
                OffsetDateTime.parse((String) raw.get(0)),
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

    /** Parsed intraday candle from Upstox. */
    public record IntradayCandle(
            OffsetDateTime time,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume
    ) {}
}
