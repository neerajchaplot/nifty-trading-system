package com.the3Cgrp.zupptrade.agent3.service;

import com.the3Cgrp.zupptrade.agent3.config.MonitoringProperties;
import com.the3Cgrp.zupptrade.agent3.model.LiveMarketSnapshot;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxMarketQuoteClient;
import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxOptionChainClient;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainRow;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionGreeks;
import com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxStrikeData;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fetches live market data from Upstox for a monitoring evaluation.
 *
 * One option chain call per evaluation:
 *   - Spot from underlyingSpotPrice on the first row
 *   - Short and long leg LTPs from marketData.ltp at matching strikes
 *   - Short leg IV from optionGreeks.iv (divided by 100 — Upstox returns percentage)
 * One separate call for VIX via market-quote/ltp.
 *
 * All errors are swallowed — a missing field returns null. Strategies handle nulls.
 */
@Service
public class LiveMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(LiveMarketDataService.class);

    private final UpstoxOptionChainClient optionChainClient;
    private final UpstoxMarketQuoteClient marketQuoteClient;
    private final MonitoringProperties props;

    public LiveMarketDataService(UpstoxOptionChainClient optionChainClient,
                                  UpstoxMarketQuoteClient marketQuoteClient,
                                  MonitoringProperties props) {
        this.optionChainClient = optionChainClient;
        this.marketQuoteClient = marketQuoteClient;
        this.props = props;
    }

    /**
     * On-demand fetch — used by the REST endpoint for a single trade evaluation.
     * Makes its own Upstox calls. Never throws — returns partial snapshot on API failure.
     */
    public LiveMarketSnapshot fetchSnapshot(TradeLegDto shortLeg, TradeLegDto longLeg,
                                             LocalDate expiryDate) {
        BigDecimal vix = fetchVix();

        List<UpstoxOptionChainRow> chain = fetchChain(expiryDate);
        if (chain.isEmpty()) {
            log.warn("agent3.market.chain_empty expiryDate={} — snapshot will be incomplete", expiryDate);
            return new LiveMarketSnapshot(null, vix, null, null, null);
        }

        BigDecimal spot = extractSpot(chain);
        BigDecimal shortLegLtp = extractLtp(chain, shortLeg.strike(), shortLeg.optionType());
        BigDecimal longLegLtp  = extractLtp(chain, longLeg.strike(),  longLeg.optionType());
        BigDecimal shortLegIv  = extractIv(chain, shortLeg.strike(), shortLeg.optionType());

        log.debug("agent3.market.snapshot spot={} vix={} shortLtp={} longLtp={} shortIv={}",
                spot, vix, shortLegLtp, longLegLtp, shortLegIv);

        return new LiveMarketSnapshot(spot, vix, shortLegLtp, longLegLtp, shortLegIv);
    }

    /**
     * Batch fetch — ONE Upstox call per unique expiry date.
     * Called by the scheduler at the start of each monitoring cycle so the chain data
     * is shared across all active trades with the same expiry. Never throws — a failed
     * expiry maps to an empty list; the snapshot built from it will have null fields.
     */
    public Map<LocalDate, List<UpstoxOptionChainRow>> batchFetchChains(Set<LocalDate> expiryDates) {
        Map<LocalDate, List<UpstoxOptionChainRow>> result = new HashMap<>();
        for (LocalDate expiry : expiryDates) {
            result.put(expiry, fetchChain(expiry));
        }
        return result;
    }

    /**
     * Builds a snapshot from a pre-fetched chain (batch mode).
     * The caller holds the chain + VIX from the current cycle — no new Upstox calls made here.
     */
    public LiveMarketSnapshot buildSnapshotFromChain(List<UpstoxOptionChainRow> chain,
                                                      BigDecimal vix,
                                                      TradeLegDto shortLeg,
                                                      TradeLegDto longLeg) {
        if (chain.isEmpty()) {
            log.warn("agent3.market.chain_empty_batch — snapshot will be incomplete");
            return new LiveMarketSnapshot(null, vix, null, null, null);
        }

        BigDecimal spot        = extractSpot(chain);
        BigDecimal shortLegLtp = extractLtp(chain, shortLeg.strike(), shortLeg.optionType());
        BigDecimal longLegLtp  = extractLtp(chain, longLeg.strike(),  longLeg.optionType());
        BigDecimal shortLegIv  = extractIv(chain, shortLeg.strike(), shortLeg.optionType());

        log.debug("agent3.market.snapshot_from_chain spot={} vix={} shortLtp={} longLtp={} shortIv={}",
                spot, vix, shortLegLtp, longLegLtp, shortLegIv);

        return new LiveMarketSnapshot(spot, vix, shortLegLtp, longLegLtp, shortLegIv);
    }

    /**
     * Fetches India VIX. Public so the scheduler can call it once per cycle.
     * Never throws — returns null on failure.
     */
    public BigDecimal fetchVix() {
        try {
            return marketQuoteClient.fetchVix();
        } catch (Exception e) {
            log.warn("agent3.market.vix_error error={}", e.getMessage());
            return null;
        }
    }

    private List<UpstoxOptionChainRow> fetchChain(LocalDate expiryDate) {
        try {
            return optionChainClient.fetchRaw("NSE_INDEX|Nifty 50", expiryDate);
        } catch (Exception e) {
            log.warn("agent3.market.chain_error expiryDate={} error={}", expiryDate, e.getMessage());
            return List.of();
        }
    }

    private BigDecimal extractSpot(List<UpstoxOptionChainRow> chain) {
        return chain.stream()
                .map(UpstoxOptionChainRow::underlyingSpotPrice)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .findFirst()
                .orElse(null);
    }

    private BigDecimal extractLtp(List<UpstoxOptionChainRow> chain, int strike, OptionType optionType) {
        return chain.stream()
                .filter(r -> r.strikePrice() != null && r.strikePrice().intValue() == strike)
                .findFirst()
                .map(r -> optionType == OptionType.PE ? r.putOptions() : r.callOptions())
                .map(UpstoxStrikeData::marketData)
                .map(md -> md != null ? md.ltp() : null)
                .orElse(null);
    }

    /**
     * Returns IV as decimal fraction (e.g. 0.172 for 17.2%).
     * Upstox returns IV as percentage — we divide by 100 here.
     */
    private BigDecimal extractIv(List<UpstoxOptionChainRow> chain, int strike, OptionType optionType) {
        Optional<UpstoxOptionGreeks> greeks = chain.stream()
                .filter(r -> r.strikePrice() != null && r.strikePrice().intValue() == strike)
                .findFirst()
                .map(r -> optionType == OptionType.PE ? r.putOptions() : r.callOptions())
                .map(UpstoxStrikeData::optionGreeks);

        if (greeks.isEmpty() || greeks.get().iv() == null) return null;

        BigDecimal ivPct = greeks.get().iv();
        if (ivPct.compareTo(BigDecimal.ZERO) <= 0) return null;

        // Upstox returns IV as percentage (e.g. 17.2) — convert to decimal for Black-Scholes
        return ivPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }
}
