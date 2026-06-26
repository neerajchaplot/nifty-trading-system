package com.the3Cgrp.zupptrade.agent1.client;

import com.the3Cgrp.zupptrade.core.upstox.client.UpstoxMarketQuoteClient;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Agent1 adapter over core-module's UpstoxOptionChainClient.
 * Computes VixRegime from the raw VIX level (domain logic stays in agent1).
 *
 * Bean name is explicit to avoid collision with the identically-named bean
 * registered by UpstoxAutoConfiguration in core-module.
 */
@Component("agent1OptionChainClient")
public class UpstoxOptionChainClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxOptionChainClient.class);

    private static final BigDecimal VIX_LOW    = new BigDecimal("13");
    private static final BigDecimal VIX_NORMAL = new BigDecimal("18");
    private static final BigDecimal VIX_HIGH   = new BigDecimal("24");

    private final com.the3Cgrp.zupptrade.core.upstox.client.UpstoxOptionChainClient coreClient;
    private final UpstoxMarketQuoteClient marketQuoteClient;

    public UpstoxOptionChainClient(
            com.the3Cgrp.zupptrade.core.upstox.client.UpstoxOptionChainClient coreClient,
            UpstoxMarketQuoteClient marketQuoteClient) {
        this.coreClient = coreClient;
        this.marketQuoteClient = marketQuoteClient;
    }

    /**
     * Fetches option chain summary for the given expiry date.
     * Returns null on any error — ScoringPipeline handles null via null-safe builder.
     *
     * futuresPremium is computed here (not in core-module) by calling the futures LTP endpoint
     * directly and subtracting spot. This keeps business-logic fetching in the agent layer.
     *
     * @param vixPrevLevel previous session VIX level stored in the last Agent1Signal (may be null)
     */
    public OptionChainSummary fetch(LocalDate expiryDate, BigDecimal vixPrevLevel) {
        com.the3Cgrp.zupptrade.core.upstox.model.chain.UpstoxOptionChainSummary core =
                coreClient.fetch(expiryDate);
        if (core == null) return null;

        // Fall back to the previous session's VIX when live LTP is unavailable (market closed)
        BigDecimal effectiveVix = core.vixLevel() != null ? core.vixLevel() : vixPrevLevel;
        VixRegime regime = toVixRegime(effectiveVix);
        BigDecimal futuresPremium = computeFuturesPremium(core.spot());

        if (core.vixLevel() == null && vixPrevLevel != null) {
            log.debug("agent1.vix.using_prev_level prevVix={}", vixPrevLevel);
        }

        return new OptionChainSummary(
                core.spot(),
                futuresPremium,
                core.pcr(),
                core.maxPain(),
                effectiveVix,
                vixPrevLevel,
                regime,
                core.callOiChange(),
                core.putOiChange()
        );
    }

    /**
     * Fetches Nifty front-month futures LTP and returns the premium over spot.
     * Returns null when spot is unavailable, markets are closed, or the LTP call fails.
     */
    private BigDecimal computeFuturesPremium(BigDecimal spot) {
        if (spot == null) return null;
        String futuresKey = com.the3Cgrp.zupptrade.core.upstox.client.UpstoxOptionChainClient
                .buildNiftyFuturesKey(LocalDate.now());
        BigDecimal futuresLtp = marketQuoteClient.fetchLtp(futuresKey);
        if (futuresLtp == null) {
            log.debug("agent1.futures.ltp.unavailable key={}", futuresKey);
            return null;
        }
        BigDecimal premium = futuresLtp.subtract(spot);
        log.debug("agent1.futures.premium key={} ltp={} spot={} premium={}", futuresKey, futuresLtp, spot, premium);
        return premium;
    }

    /** Overload for callers that don't have a previous VIX level. */
    public OptionChainSummary fetch(LocalDate expiryDate) {
        return fetch(expiryDate, null);
    }

    private VixRegime toVixRegime(BigDecimal vix) {
        if (vix == null) return VixRegime.NORMAL;
        if (vix.compareTo(VIX_LOW) < 0)    return VixRegime.LOW;
        if (vix.compareTo(VIX_NORMAL) < 0) return VixRegime.NORMAL;
        if (vix.compareTo(VIX_HIGH) < 0)   return VixRegime.HIGH;
        return VixRegime.EXTREME;
    }

    /** Aggregated scoring inputs extracted from the full option chain response. */
    public record OptionChainSummary(
            BigDecimal spot,
            BigDecimal futuresPremium,
            BigDecimal pcr,
            Integer maxPain,
            BigDecimal vixLevel,
            BigDecimal vixPrevLevel,
            VixRegime vixRegime,
            BigDecimal callOiChange,
            BigDecimal putOiChange
    ) {}
}
