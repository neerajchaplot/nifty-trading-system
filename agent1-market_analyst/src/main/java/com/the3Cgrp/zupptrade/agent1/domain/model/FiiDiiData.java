package com.the3Cgrp.zupptrade.agent1.domain.model;

import java.math.BigDecimal;

/**
 * FII and DII data for the most recent trading session, plus 5-day trend.
 *
 * Produced by FiiDiiService (fetch + persist + trend).
 * Consumed by ScoringPipeline → MarketInputs → InstitutionalFlowScorer (Tier 2).
 *
 * fiiNetFutures  — INDEX_FUTURES net flow (₹Cr); ±500 Cr threshold for scoring
 * fiiNetOptions  — INDEX_OPTIONS net flow (₹Cr); ±500 Cr threshold
 * diiNet         — DII cash net flow (₹Cr); ±500 Cr threshold
 * fiiLongRatio   — FII long / (long + short) futures OI
 * futuresTrend   — 5-day trend derived from fii_dii_snapshots table;
 *                  null only when called directly via UpstoxFiiDiiClient.fetchLatest()
 *                  (connectivity tests); always populated via FiiDiiService
 */
public record FiiDiiData(
        BigDecimal fiiNetFutures,
        BigDecimal fiiNetOptions,
        BigDecimal diiNet,
        BigDecimal fiiLongRatio,
        FiiDiiTrend futuresTrend
) {}
