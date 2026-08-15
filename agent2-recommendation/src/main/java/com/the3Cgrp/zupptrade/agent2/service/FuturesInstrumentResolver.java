package com.the3Cgrp.zupptrade.agent2.service;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxFuturesContractClient;
import com.the3Cgrp.zupptrade.core.upstox.model.contract.UpstoxFuturesContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves the current-month Nifty futures instrument_key dynamically from Upstox — the futures
 * analog of how options resolve strike keys from the option chain (no manual reference_data seed).
 *
 * Calls the Instrument Search API, keeps only the exact NIFTY underlying (drops BANKNIFTY/FINNIFTY
 * that also match the "NIFTY" query), and picks the front contract: the nearest expiry ≥ today.
 * Result is cached per trading day so repeated recommends don't re-hit the API. A missing/failed
 * lookup returns null (logged) — the card still builds; Agent 5 alerts if it's truly unresolved.
 */
@Component
public class FuturesInstrumentResolver {

    private static final Logger log = LoggerFactory.getLogger(FuturesInstrumentResolver.class);
    private static final String NIFTY_UNDERLYING = "NIFTY";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final UpstoxFuturesContractClient contractClient;
    private final Clock clock;

    // Per-day cache: the instrument_key is stable within a trading day (Upstox refreshes at ~6 AM).
    private volatile LocalDate cachedFor;
    private volatile String cachedKey;

    public FuturesInstrumentResolver(UpstoxFuturesContractClient contractClient, Clock clock) {
        this.contractClient = contractClient;
        this.clock = clock;
    }

    /** @return the current-month Nifty fut instrument_key, or null if unresolved (logged). */
    public String resolveCurrentMonthFut() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
        if (today.equals(cachedFor) && cachedKey != null) {
            return cachedKey;
        }
        String key = fetchCurrentMonth(today);
        if (key != null) {
            cachedKey = key;
            cachedFor = today;
        }
        return key;
    }

    private String fetchCurrentMonth(LocalDate today) {
        List<UpstoxFuturesContract> contracts = contractClient.fetchNiftyFutures();
        String key = contracts.stream()
                .filter(c -> c.instrumentKey() != null && c.expiry() != null)
                .filter(c -> NIFTY_UNDERLYING.equalsIgnoreCase(c.underlyingSymbol()))
                .filter(c -> !c.expiry().isBefore(today))   // expiry >= today
                .min(Comparator.comparing(UpstoxFuturesContract::expiry))
                .map(UpstoxFuturesContract::instrumentKey)
                .orElse(null);

        if (key == null) {
            log.warn("futures.instrument.unresolved — no current-month NIFTY FUT from Upstox search "
                    + "(candidates={}); card built without a fut token, resolve before Agent 5 execution",
                    contracts.size());
        } else {
            log.info("futures.instrument.resolved instrumentKey={}", key);
        }
        return key;
    }
}
