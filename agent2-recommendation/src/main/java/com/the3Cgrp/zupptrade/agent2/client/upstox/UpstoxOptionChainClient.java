package com.the3Cgrp.zupptrade.agent2.client.upstox;

import com.the3Cgrp.zupptrade.agent2.client.OptionChainClient;
import com.the3Cgrp.zupptrade.agent2.client.model.OptionChainData;
import com.the3Cgrp.zupptrade.agent2.client.model.StrikeData;
import com.the3Cgrp.zupptrade.agent2.client.upstox.response.UpstoxLegData;
import com.the3Cgrp.zupptrade.agent2.client.upstox.response.UpstoxOptionChainResponse;
import com.the3Cgrp.zupptrade.agent2.client.upstox.response.UpstoxStrikeEntry;
import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.exception.MarketDataUnavailableException;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class UpstoxOptionChainClient implements OptionChainClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxOptionChainClient.class);
    private static final DateTimeFormatter UPSTOX_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RestClient restClient;
    private final TradingConfig config;

    public UpstoxOptionChainClient(RestClient upstoxRestClient, TradingConfig config) {
        this.restClient = upstoxRestClient;
        this.config = config;
    }

    @Override
    public OptionChainData fetch(LocalDate expiryDate) {
        String expiryStr = expiryDate.format(UPSTOX_DATE_FORMAT);
        int maxRetries = config.getUpstox().getRetryMax();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                UpstoxOptionChainResponse response = restClient.get()
                        .uri("/v2/option/chain?instrument_key={key}&expiry_date={expiry}",
                                config.getUpstox().getNiftyInstrumentKey(), expiryStr)
                        .retrieve()
                        .body(UpstoxOptionChainResponse.class);

                log.debug("option.chain.fetched",
                        kv("expiry", expiryStr),
                        kv("attempt", attempt),
                        kv("strikeCount", response != null && response.data() != null ? response.data().size() : 0));

                return mapToOptionChainData(response, expiryDate);

            } catch (RestClientException ex) {
                lastException = ex;
                log.warn("option.chain.fetch.retry",
                        kv("expiry", expiryStr),
                        kv("attempt", attempt),
                        kv("maxRetries", maxRetries),
                        kv("error", ex.getMessage()));
            }
        }

        throw new MarketDataUnavailableException(
                "Failed to fetch option chain for expiry " + expiryStr + " after " + maxRetries + " attempts",
                lastException);
    }

    private OptionChainData mapToOptionChainData(UpstoxOptionChainResponse response, LocalDate expiryDate) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new MarketDataUnavailableException("Empty option chain response from data provider");
        }

        List<StrikeData> calls = new ArrayList<>();
        List<StrikeData> puts = new ArrayList<>();
        double underlyingSpot = 0;

        for (UpstoxStrikeEntry entry : response.data()) {
            int strike = (int) Math.round(entry.strikePrice());

            // underlyingSpotPrice is the actual Nifty spot carried in every strike row (nullable on some entries)
            if (underlyingSpot == 0 && entry.underlyingSpotPrice() != null && entry.underlyingSpotPrice() > 0) {
                underlyingSpot = entry.underlyingSpotPrice();
            }

            if (entry.callOptions() != null && entry.callOptions().marketData() != null) {
                calls.add(mapStrike(strike, OptionType.CE, entry.callOptions()));
            }
            if (entry.putOptions() != null && entry.putOptions().marketData() != null) {
                puts.add(mapStrike(strike, OptionType.PE, entry.putOptions()));
            }
        }

        calls.sort(Comparator.comparingInt(StrikeData::strike));
        puts.sort(Comparator.comparingInt(StrikeData::strike));

        // ATM strike = strike with lowest absolute delta from 0.5 on call side
        StrikeData atmCall = calls.stream()
                .min(Comparator.comparingDouble(s -> Math.abs(s.delta().doubleValue() - 0.5)))
                .orElseThrow(() -> new MarketDataUnavailableException("Cannot determine ATM strike from option chain"));

        StrikeData atmPut = puts.stream()
                .filter(s -> s.strike() == atmCall.strike())
                .findFirst()
                .orElse(puts.get(puts.size() / 2));

        return new OptionChainData(
                BigDecimal.valueOf(underlyingSpot),
                expiryDate,
                calls,
                puts,
                atmCall.strike(),
                atmCall.ltp(),
                atmPut.ltp()
        );
    }

    private StrikeData mapStrike(int strike, OptionType optionType, UpstoxLegData leg) {
        UpstoxLegData.UpstoxMarketData md = leg.marketData();
        UpstoxLegData.UpstoxOptionGreeks greeks = leg.optionGreeks();
        return new StrikeData(
                strike,
                optionType,
                BigDecimal.valueOf(md.ltp()),
                // Upstox returns IV as percentage (e.g. 17.77 = 17.77%) — divide by 100 for B-S formulas expecting decimal
                greeks != null ? BigDecimal.valueOf(greeks.iv() / 100.0)  : BigDecimal.ZERO,
                greeks != null ? BigDecimal.valueOf(greeks.delta())        : BigDecimal.ZERO,  // already decimal (-0.169)
                // Upstox returns PoP as percentage (e.g. 82.6 = 82.6%) — divide by 100 for gate checks expecting decimal
                greeks != null ? BigDecimal.valueOf(greeks.pop() / 100.0) : BigDecimal.ZERO,
                BigDecimal.valueOf(md.oi()),
                BigDecimal.valueOf(md.bidPrice()),
                BigDecimal.valueOf(md.askPrice()),
                leg.instrumentKey()  // carried through for Agent 5 order placement
        );
    }
}
