package com.the3Cgrp.zupptrade.core.upstox.client;

import com.the3Cgrp.zupptrade.core.upstox.model.UpstoxApiResponse;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxMarketData;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainRow;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * GET /v2/option/chain?instrument_key=...&expiry_date=...
 *
 * Fetches the full Nifty 50 option chain for a given expiry date and computes:
 *   - spot price (underlying_spot_price from first row)
 *   - PCR = sum(all put OI) / sum(all call OI)
 *   - max pain = strike minimising total payout to option buyers
 *   - OI changes (call and put) since previous session
 *   - India VIX from market-quote/ltp (separate call)
 *
 * futuresPremium is NOT computed here — it is the responsibility of the agent1
 * UpstoxOptionChainClient wrapper, which calls buildNiftyFuturesKey() + fetchLtp()
 * and computes (futuresLtp - spot) before populating its own OptionChainSummary.
 */
public class UpstoxOptionChainClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxOptionChainClient.class);
    private static final String NIFTY_50_KEY = "NSE_INDEX|Nifty 50";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RestClient upstoxRestClient;
    private final UpstoxMarketQuoteClient marketQuoteClient;

    public UpstoxOptionChainClient(RestClient upstoxRestClient) {
        this.upstoxRestClient = upstoxRestClient;
        this.marketQuoteClient = new UpstoxMarketQuoteClient(upstoxRestClient);
    }

    /** Fetches and summarises the Nifty 50 option chain for the given weekly expiry. */
    public UpstoxOptionChainSummary fetch(LocalDate expiryDate) {
        List<UpstoxOptionChainRow> rows = fetchRaw(NIFTY_50_KEY, expiryDate);
        if (rows.isEmpty()) {
            return null;
        }

        BigDecimal spot = rows.stream()
                .filter(r -> r.underlyingSpotPrice() != null)
                .map(UpstoxOptionChainRow::underlyingSpotPrice)
                .findFirst()
                .orElse(null);

        BigDecimal pcr       = computePcr(rows);
        Integer    maxPain   = computeMaxPain(rows);
        long       callOiChg = sumOiChange(rows, true);
        long       putOiChg  = sumOiChange(rows, false);
        BigDecimal vixLevel  = marketQuoteClient.fetchVix();

        return new UpstoxOptionChainSummary(
                spot,
                pcr,
                maxPain,
                vixLevel,
                BigDecimal.valueOf(callOiChg),
                BigDecimal.valueOf(putOiChg)
        );
    }

    /** Returns raw option chain rows — useful for inspection and tests. */
    public List<UpstoxOptionChainRow> fetchRaw(String instrumentKey, LocalDate expiryDate) {
        try {
            // Pass raw instrumentKey — RestClient URI template encoding handles | and spaces.
            // Manual pre-encoding before a URI template variable causes double-encoding (%7C → %257C).
            UpstoxApiResponse<List<UpstoxOptionChainRow>> response = upstoxRestClient.get()
                    .uri("/v2/option/chain?instrument_key={key}&expiry_date={date}",
                            instrumentKey, expiryDate.format(DATE_FMT))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.isSuccess() || response.data() == null) {
                log.warn("upstox.option-chain.empty_response instrument={} expiry={} status={}",
                        instrumentKey, expiryDate, response != null ? response.status() : "null");
                return List.of();
            }
            return response.data();
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("upstox.token.expired instrument={} expiry={} — access token is invalid or expired. " +
                      "Paste a fresh token into application-local.yml and restart.", instrumentKey, expiryDate);
            return List.of();
        } catch (Exception e) {
            log.warn("upstox.option-chain.error instrument={} expiry={} error={}",
                    instrumentKey, expiryDate, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Builds the NSE F&O instrument key for the Nifty front-month futures contract.
     * Format: {@code NSE_FO|NIFTY{YY}{MON}FUT}  e.g. {@code NSE_FO|NIFTY26MAYFUT}
     *
     * Rollover rule: on and after the last Thursday of the current month (expiry day),
     * the front month rolls to the following calendar month.
     * Public so integration tests and other modules can resolve the active contract key.
     */
    public static String buildNiftyFuturesKey(LocalDate date) {
        LocalDate expiryThursday = lastThursdayOf(date.getYear(), date.getMonth());
        // Roll over strictly after expiry Thursday (contract still valid on expiry day until close)
        LocalDate contractMonth = !date.isAfter(expiryThursday)
                ? date
                : date.withDayOfMonth(1).plusMonths(1);
        String yy  = String.format("%02d", contractMonth.getYear() % 100);
        String mon = contractMonth.getMonth()
                                  .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                                  .toUpperCase(Locale.ENGLISH);
        return "NSE_FO|NIFTY" + yy + mon + "FUT";
    }

    /** Returns the last Thursday of the given month. */
    private static LocalDate lastThursdayOf(int year, Month month) {
        LocalDate lastDay = LocalDate.of(year, month, month.length(Year.isLeap(year)));
        int daysBack = (lastDay.getDayOfWeek().getValue() - DayOfWeek.THURSDAY.getValue() + 7) % 7;
        return lastDay.minusDays(daysBack);
    }

    // --- PCR: sum(put OI) / sum(call OI) ---

    private BigDecimal computePcr(List<UpstoxOptionChainRow> rows) {
        long totalCallOi = rows.stream()
                .filter(r -> r.callOptions() != null && r.callOptions().marketData() != null)
                .mapToLong(r -> r.callOptions().marketData().oi())
                .sum();
        long totalPutOi = rows.stream()
                .filter(r -> r.putOptions() != null && r.putOptions().marketData() != null)
                .mapToLong(r -> r.putOptions().marketData().oi())
                .sum();
        if (totalCallOi == 0) return null;
        return BigDecimal.valueOf(totalPutOi)
                .divide(BigDecimal.valueOf(totalCallOi), 4, RoundingMode.HALF_UP);
    }

    // --- Max Pain: strike price minimising total payout from sellers to buyers ---

    private Integer computeMaxPain(List<UpstoxOptionChainRow> rows) {
        List<BigDecimal> strikes = rows.stream()
                .map(UpstoxOptionChainRow::strikePrice)
                .filter(s -> s != null)
                .sorted()
                .toList();
        if (strikes.isEmpty()) return null;

        BigDecimal minPayout = null;
        BigDecimal maxPainStrike = null;

        for (BigDecimal candidatePrice : strikes) {
            BigDecimal totalPayout = BigDecimal.ZERO;
            for (UpstoxOptionChainRow row : rows) {
                if (row.strikePrice() == null) continue;
                BigDecimal strike = row.strikePrice();

                // Payout to call buyers: call_OI * max(0, candidatePrice - strike)
                if (row.callOptions() != null && row.callOptions().marketData() != null) {
                    long callOi = row.callOptions().marketData().oi();
                    BigDecimal callPayout = candidatePrice.subtract(strike).max(BigDecimal.ZERO);
                    totalPayout = totalPayout.add(callPayout.multiply(BigDecimal.valueOf(callOi)));
                }
                // Payout to put buyers: put_OI * max(0, strike - candidatePrice)
                if (row.putOptions() != null && row.putOptions().marketData() != null) {
                    long putOi = row.putOptions().marketData().oi();
                    BigDecimal putPayout = strike.subtract(candidatePrice).max(BigDecimal.ZERO);
                    totalPayout = totalPayout.add(putPayout.multiply(BigDecimal.valueOf(putOi)));
                }
            }
            if (minPayout == null || totalPayout.compareTo(minPayout) < 0) {
                minPayout = totalPayout;
                maxPainStrike = candidatePrice;
            }
        }
        return maxPainStrike != null ? maxPainStrike.intValue() : null;
    }

    private long sumOiChange(List<UpstoxOptionChainRow> rows, boolean calls) {
        return rows.stream()
                .map(r -> calls ? r.callOptions() : r.putOptions())
                .filter(s -> s != null && s.marketData() != null)
                .mapToLong(s -> {
                    UpstoxMarketData md = s.marketData();
                    return md.oi() - md.prevOi();
                })
                .sum();
    }
}
