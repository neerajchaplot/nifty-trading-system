package com.the3Cgrp.zupptrade.agent2.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.shared.enums.IvRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Computes Historical Volatility (HV) from Upstox daily OHLC data.
 *
 * <p>HV calculation (industry standard):
 * <ol>
 *   <li>Fetch the most recent 21 daily close prices (gives 20 log-return data points)</li>
 *   <li>Compute daily log returns: r[i] = ln(close[i] / close[i-1])</li>
 *   <li>Compute sample standard deviation of the 20 log returns</li>
 *   <li>Annualise: HV = std_dev × √252</li>
 * </ol>
 * Result is expressed as a decimal (e.g. 0.1540 = 15.40% annualised volatility).
 *
 * <p>IV/HV regime derivation uses VIX/100 as the implied volatility proxy (Decision 2, Option A).
 * Thresholds are read from {@link TradingConfig}:
 * <ul>
 *   <li>RICH  — ratio > {@code ivHvRichThreshold}  (default 1.05)</li>
 *   <li>CHEAP — ratio < {@code ivHvCheapThreshold} (default 0.85)</li>
 *   <li>FAIR  — otherwise</li>
 * </ul>
 */
@Service
public class VolatilityService {

    private static final Logger log = LoggerFactory.getLogger(VolatilityService.class);

    private static final String NIFTY_50_KEY = "NSE_INDEX|Nifty 50";
    private static final int HV_LOOKBACK = 20;                     // 20 log returns → need 21 closes
    private static final double TRADING_DAYS_PER_YEAR = 252.0;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RestClient upstoxRestClient;
    private final TradingConfig config;

    public VolatilityService(RestClient upstoxRestClient, TradingConfig config) {
        this.upstoxRestClient = upstoxRestClient;
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetches 20 trading days of Nifty 50 close prices from Upstox and returns the
     * 20-day annualised Historical Volatility as a decimal (e.g. 0.1540 = 15.40%).
     *
     * <p>Returns {@code null} if fewer than 2 data points are available (weekend/holiday
     * or API failure). Callers should treat null as "unavailable" and fall back to FAIR regime.
     */
    public BigDecimal computeHv20d() {
        // Fetch (lookback + 1) closes: 21 closes → 20 log returns.
        // Add 30-day calendar buffer to account for weekends and market holidays.
        LocalDate to   = LocalDate.now();
        LocalDate from = to.minusDays(HV_LOOKBACK + 30L);

        List<BigDecimal> closes = fetchCloses(from, to);

        if (closes.size() < 2) {
            log.warn("volatility.hv.insufficient_data closesAvailable={}", closes.size());
            return null;
        }

        // Use only the most recent (HV_LOOKBACK + 1) closes, newest first from the API.
        // Limit to at most 21 data points.
        int limit = Math.min(closes.size(), HV_LOOKBACK + 1);
        List<BigDecimal> usedCloses = closes.subList(0, limit);

        double[] logReturns = computeLogReturns(usedCloses);
        double hv = annualise(stdDev(logReturns));

        BigDecimal hvDecimal = BigDecimal.valueOf(hv).setScale(6, RoundingMode.HALF_UP);

        log.info("volatility.hv.computed",
                kv("hv20d", hvDecimal),
                kv("dataPoints", logReturns.length),
                kv("from", from.toString()),
                kv("to", to.toString()));

        return hvDecimal;
    }

    /**
     * Derives the IV regime by comparing VIX (as annualised implied vol) against HV.
     *
     * <p>IV = vix / 100 (VIX is expressed as a percentage, e.g. 15.5 → IV = 0.155).
     * <p>Returns FAIR if {@code hv} is null or zero.
     */
    public IvRegime deriveIvRegime(BigDecimal vix, BigDecimal hv) {
        if (vix == null || hv == null || hv.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("volatility.ivRegime.unavailable vix={} hv={} → FAIR", vix, hv);
            return IvRegime.FAIR;
        }

        // VIX is an annualised vol expressed as a percentage → divide by 100 to get decimal
        BigDecimal iv = vix.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal ratio = iv.divide(hv, 4, RoundingMode.HALF_UP);

        IvRegime regime;
        if (ratio.compareTo(config.getIvHvRichThreshold()) > 0) {
            regime = IvRegime.RICH;
        } else if (ratio.compareTo(config.getIvHvCheapThreshold()) < 0) {
            regime = IvRegime.CHEAP;
        } else {
            regime = IvRegime.FAIR;
        }

        log.info("volatility.ivRegime.derived",
                kv("vix", vix),
                kv("hv", hv),
                kv("iv", iv),
                kv("ivHvRatio", ratio),
                kv("regime", regime));

        return regime;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fetches daily close prices for Nifty 50 from Upstox historical API.
     * Returns closes newest-first (API order preserved).
     */
    private List<BigDecimal> fetchCloses(LocalDate from, LocalDate to) {
        try {
            HistoricalResponse response = upstoxRestClient.get()
                    .uri("/v2/historical-candle/{key}/day/{to}/{from}",
                            NIFTY_50_KEY,
                            to.format(DATE_FMT),
                            from.format(DATE_FMT))
                    .retrieve()
                    .body(HistoricalResponse.class);

            if (response == null || response.data() == null
                    || response.data().candles() == null
                    || response.data().candles().isEmpty()) {
                log.warn("volatility.historical.empty_response from={} to={}", from, to);
                return List.of();
            }

            return response.data().candles().stream()
                    .map(this::extractClose)
                    .toList();

        } catch (Exception e) {
            log.warn("volatility.historical.fetch_failed error={}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Candle format: [timestamp, open, high, low, close, volume, oi]
     * Close is at index 4.
     */
    private BigDecimal extractClose(List<Object> candle) {
        Object raw = candle.get(4);
        return new BigDecimal(raw.toString());
    }

    /**
     * Computes daily log returns from close prices (newest-first order).
     * r[i] = ln(closes[i] / closes[i+1]) — i.e., today / yesterday.
     */
    private double[] computeLogReturns(List<BigDecimal> closes) {
        int n = closes.size() - 1;   // number of log returns
        double[] returns = new double[n];
        for (int i = 0; i < n; i++) {
            double current  = closes.get(i).doubleValue();
            double previous = closes.get(i + 1).doubleValue();
            returns[i] = Math.log(current / previous);
        }
        return returns;
    }

    /** Sample standard deviation (Bessel's correction, n-1 denominator). */
    private double stdDev(double[] values) {
        if (values.length < 2) return 0.0;
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;

        double sumSq = 0;
        for (double v : values) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / (values.length - 1));
    }

    /** Annualise a daily standard deviation by multiplying by √252. */
    private double annualise(double dailyStd) {
        return dailyStd * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }

    // -------------------------------------------------------------------------
    // Inline response DTOs (used only by this service)
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HistoricalResponse(
            @JsonProperty("status") String status,
            @JsonProperty("data") HistoricalData data
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record HistoricalData(
                @JsonProperty("candles") List<List<Object>> candles
        ) {}
    }
}
