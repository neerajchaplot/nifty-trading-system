package com.the3Cgrp.zupptrade.agent3.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Optional request body for POST /api/v1/agent3/evaluate/{tradeId}.
 *
 * When present, these values are used instead of fetching live data from Upstox.
 * Intended for weekend/offline testing only — production calls should omit this body.
 *
 * Fields that are null are replaced by zeros / nulls in the synthetic snapshot,
 * which the monitor strategies handle gracefully (treat missing option LTPs as
 * unavailable → conservative WATCH action).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluateOverrideRequest(

        /** Nifty 50 spot price override. Required for meaningful threshold evaluation. */
        @JsonProperty("niftySpot")
        BigDecimal niftySpot,

        /** India VIX override. If null, VIX spike detection is skipped. */
        @JsonProperty("vix")
        BigDecimal vix,

        /** Short leg current LTP (e.g. PE 23500). Required for mark-to-market P&L. */
        @JsonProperty("shortLegLtp")
        BigDecimal shortLegLtp,

        /** Long leg current LTP (e.g. PE 23400). Required for mark-to-market P&L. */
        @JsonProperty("longLegLtp")
        BigDecimal longLegLtp,

        /** Short leg IV as decimal (e.g. 0.172 = 17.2%). Used for live PoP calc. */
        @JsonProperty("shortLegIv")
        BigDecimal shortLegIv

) {
    public boolean hasOverrides() {
        return niftySpot != null;
    }
}
