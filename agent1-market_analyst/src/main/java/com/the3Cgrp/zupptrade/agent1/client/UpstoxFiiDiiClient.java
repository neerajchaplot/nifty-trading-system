package com.the3Cgrp.zupptrade.agent1.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiData;
import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Fetches FII and DII activity data from Upstox Market Information APIs.
 * Endpoints: GET /v2/market/fii and GET /v2/market/dii
 *
 * Response structure: { "status": "success", "data": { "<data_type>": [ {...}, ... ] } }
 *   - entries are returned newest-first; entries.get(0) = most recent trading session
 *   - time_stamp is Unix epoch milliseconds
 *
 * Data types queried:
 *   NSE_FO|INDEX_FUTURES → fiiNetFutures (₹Cr) + fiiLongRatio (total_long/short_contracts)
 *   NSE_FO|INDEX_OPTIONS → fiiNetOptions (₹Cr)
 *   NSE_EQ|CASH (DII)   → diiNet (₹Cr)
 *
 * Net flow = buy_amount - sell_amount (₹Cr). Threshold: ±500 Cr (CLAUDE.md Tier 2).
 * FII long ratio = total_long_contracts / (total_long + total_short). Threshold: 40–60%.
 *
 * Date window: queries last 7 days so weekends and holidays are handled automatically —
 * entries.get(0) is always the most recent trading session regardless of when called.
 *
 * On any error (token expired, weekend, empty response) returns null per field —
 * caller maps null to score 0.
 *
 * FiiDiiData is now in domain/model (not an inner record here).
 * FiiDiiRawFetch (inner record) carries all entries for FiiDiiService persistence.
 */
@Component
public class UpstoxFiiDiiClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxFiiDiiClient.class);

    private static final String FII_ENDPOINT = "/v2/market/fii";
    private static final String DII_ENDPOINT = "/v2/market/dii";
    private static final String INTERVAL     = "1D";

    // Public — used by FiiDiiService and FiiDiiSnapshotRepository queries
    public static final String DATA_TYPE_FII_FUTURES = "NSE_FO|INDEX_FUTURES";
    public static final String DATA_TYPE_FII_OPTIONS = "NSE_FO|INDEX_OPTIONS";
    public static final String DATA_TYPE_DII_CASH    = "NSE_EQ|CASH";

    private final RestClient upstoxRestClient;

    public UpstoxFiiDiiClient(@Qualifier("upstoxRestClient") RestClient upstoxRestClient) {
        this.upstoxRestClient = upstoxRestClient;
    }

    // -------------------------------------------------------------------------
    // Primary API — used by FiiDiiService (fetch all entries for persistence)
    // -------------------------------------------------------------------------

    /**
     * Fetches all entries in the 7-day window for all three segments.
     * Returns a FiiDiiRawFetch containing the full list per segment —
     * FiiDiiService uses these lists to persist daily snapshots and compute the trend.
     *
     * Returns null if ALL three segments failed (likely token expiry).
     */
    public FiiDiiRawFetch fetchAllEntries() {
        String fromDate = LocalDate.now().minusDays(7).toString();

        List<MarketFlowEntry> futuresEntries = fetchEntries(FII_ENDPOINT, DATA_TYPE_FII_FUTURES, fromDate);
        List<MarketFlowEntry> optionsEntries = fetchEntries(FII_ENDPOINT, DATA_TYPE_FII_OPTIONS, fromDate);
        List<MarketFlowEntry> diiEntries     = fetchEntries(DII_ENDPOINT, DATA_TYPE_DII_CASH,    fromDate);

        if (futuresEntries.isEmpty() && optionsEntries.isEmpty() && diiEntries.isEmpty()) {
            log.warn("upstox.fii-dii.all_empty fromDate={} — check for upstox.token.expired in logs.", fromDate);
            return null;
        }

        return new FiiDiiRawFetch(futuresEntries, optionsEntries, diiEntries);
    }

    // -------------------------------------------------------------------------
    // Connectivity / legacy API — used by UpstoxFiiDiiConnectivityTest
    // -------------------------------------------------------------------------

    /**
     * Convenience method: fetches the single most recent entry per segment and
     * returns a FiiDiiData without a trend (trend = null).
     * Used by UpstoxFiiDiiConnectivityTest to verify Upstox API connectivity.
     * Production scoring uses FiiDiiService.fetchAndPersist() which adds the trend.
     */
    public FiiDiiData fetchLatest() {
        String fromDate = LocalDate.now().minusDays(7).toString();

        List<MarketFlowEntry> futures = fetchEntries(FII_ENDPOINT, DATA_TYPE_FII_FUTURES, fromDate);
        List<MarketFlowEntry> options = fetchEntries(FII_ENDPOINT, DATA_TYPE_FII_OPTIONS, fromDate);
        List<MarketFlowEntry> dii     = fetchEntries(DII_ENDPOINT, DATA_TYPE_DII_CASH,    fromDate);

        MarketFlowEntry latestFutures = futures.isEmpty() ? null : futures.get(0);
        MarketFlowEntry latestOptions = options.isEmpty() ? null : options.get(0);
        MarketFlowEntry latestDii     = dii.isEmpty()     ? null : dii.get(0);

        BigDecimal fiiNetFutures = netFlow(latestFutures);
        BigDecimal fiiNetOptions = netFlow(latestOptions);
        BigDecimal diiNet        = netFlow(latestDii);
        BigDecimal fiiLongRatio  = computeLongRatio(latestFutures);

        if (fiiNetFutures == null && fiiNetOptions == null && diiNet == null) {
            log.warn("upstox.fii-dii.all_null fromDate={} — all endpoints returned no data.", fromDate);
            return null;
        }

        log.info("upstox.fii-dii.fetched fiiNetFutures={} fiiNetOptions={} diiNet={} fiiLongRatio={}",
                fiiNetFutures, fiiNetOptions, diiNet, fiiLongRatio);
        return new FiiDiiData(fiiNetFutures, fiiNetOptions, diiNet, fiiLongRatio, null);
    }

    // -------------------------------------------------------------------------
    // Internal — fetch all entries for one segment
    // -------------------------------------------------------------------------

    private List<MarketFlowEntry> fetchEntries(String endpoint, String dataType, String fromDate) {
        try {
            UpstoxApiResponse<Map<String, List<MarketFlowEntry>>> response =
                    upstoxRestClient.get()
                            .uri(u -> u.path(endpoint)
                                    .queryParam("data_type", dataType)
                                    .queryParam("interval", INTERVAL)
                                    .queryParam("from", fromDate)
                                    .build())
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.isSuccess() || response.data() == null) {
                log.warn("upstox.fii-dii.empty endpoint={} data_type={}", endpoint, dataType);
                return List.of();
            }

            List<MarketFlowEntry> entries = response.data().get(dataType);
            if (entries == null || entries.isEmpty()) {
                log.warn("upstox.fii-dii.no_entries endpoint={} data_type={} keys_returned={}",
                        endpoint, dataType, response.data().keySet());
                return List.of();
            }

            log.debug("upstox.fii-dii.entries endpoint={} data_type={} count={} latest_ts={}",
                    endpoint, dataType, entries.size(), entries.get(0).timeStamp());
            return entries;

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("upstox.token.expired endpoint={} data_type={} — " +
                      "re-run upstox-auth to refresh the access token.", endpoint, dataType);
            return List.of();
        } catch (Exception e) {
            log.warn("upstox.fii-dii.error endpoint={} data_type={} error={}",
                    endpoint, dataType, e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Computation helpers (also used by FiiDiiService via static access)
    // -------------------------------------------------------------------------

    public static BigDecimal netFlow(MarketFlowEntry entry) {
        if (entry == null || entry.buyAmount() == null || entry.sellAmount() == null) return null;
        return entry.buyAmount().subtract(entry.sellAmount());
    }

    /**
     * FII long ratio from index futures: total_long / (total_long + total_short).
     * Returns null if contracts data unavailable or total is zero.
     * Typical range: 10–30% (FII structurally net-short Nifty futures as hedgers).
     */
    public static BigDecimal computeLongRatio(MarketFlowEntry entry) {
        if (entry == null
                || entry.totalLongContracts() == null
                || entry.totalShortContracts() == null) return null;
        long totalLong  = entry.totalLongContracts();
        long totalShort = entry.totalShortContracts();
        long total = totalLong + totalShort;
        if (total == 0) return null;
        return BigDecimal.valueOf(totalLong)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------------
    // Response model — one data point from /v2/market/fii or /v2/market/dii
    // -------------------------------------------------------------------------

    /**
     * time_stamp    — Unix epoch milliseconds
     * buy_amount    — ₹ Crore
     * sell_amount   — ₹ Crore
     * total_long_contracts / total_short_contracts — cumulative FII OI positions;
     *   populated for INDEX_FUTURES; zero for cash/options segments
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketFlowEntry(
            @JsonProperty("time_stamp")             Long       timeStamp,
            @JsonProperty("buy_amount")             BigDecimal buyAmount,
            @JsonProperty("sell_amount")            BigDecimal sellAmount,
            @JsonProperty("buy_contracts")          Long       buyContracts,
            @JsonProperty("sell_contracts")         Long       sellContracts,
            @JsonProperty("oi_contracts")           Long       oiContracts,
            @JsonProperty("oi_amount")              BigDecimal oiAmount,
            @JsonProperty("total_long_contracts")   Long       totalLongContracts,
            @JsonProperty("total_short_contracts")  Long       totalShortContracts
    ) {}

    // -------------------------------------------------------------------------
    // Raw fetch result — carries all entries for FiiDiiService to persist
    // -------------------------------------------------------------------------

    /**
     * All entries from the 7-day window for each segment.
     * FiiDiiService iterates these lists, converts timeStamp → tradingDate,
     * and persists any new rows to fii_dii_snapshots.
     */
    public record FiiDiiRawFetch(
            List<MarketFlowEntry> futuresEntries,
            List<MarketFlowEntry> optionsEntries,
            List<MarketFlowEntry> diiEntries
    ) {}
}
