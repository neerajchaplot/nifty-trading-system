package com.the3Cgrp.zupptrade.agent1.pipeline;

import com.the3Cgrp.zupptrade.agent1.client.GiftNiftyClient;
import com.the3Cgrp.zupptrade.agent1.client.MarketauxClient;
import com.the3Cgrp.zupptrade.agent1.client.MarketauxClient.NseiSentiment;
import com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiData;
import com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiTrend;
import com.the3Cgrp.zupptrade.agent1.service.FiiDiiService;
import com.the3Cgrp.zupptrade.agent1.client.UpstoxHistoricalClient;
import com.the3Cgrp.zupptrade.agent1.client.UpstoxOptionChainClient;
import com.the3Cgrp.zupptrade.agent1.composer.SignalComposer;
import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.CommentarySignal;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.OhlcCandle;
import com.the3Cgrp.zupptrade.agent1.domain.model.PrecomputedIndicators;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import com.the3Cgrp.zupptrade.agent1.dto.ScoreRequestDto;
import com.the3Cgrp.zupptrade.agent1.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.agent1.scoring.TierScorer;
import com.the3Cgrp.zupptrade.agent1.service.CommentaryExtractorService;
import com.the3Cgrp.zupptrade.agent1.service.TechnicalIndicatorService;
import com.the3Cgrp.zupptrade.core.expiry.ExpiryDateService;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Template Method Pattern — orchestrates the full scoring run in a fixed sequence:
 *   1. Fetch market inputs from all data sources
 *   2. Compute TA4J indicators
 *   3. Score each tier (Strategy Pattern via TierScorer implementations)
 *   4. Compose final signal (SignalComposer)
 *   5. Persist to agent1_signals table
 *
 * External API calls are never inside a JPA transaction. Data is collected first,
 * then the compose+persist step runs in a single transaction.
 */
@Component
public class ScoringPipeline {

    private static final Logger log = LoggerFactory.getLogger(ScoringPipeline.class);

    private final List<TierScorer> tierScorers;
    private final SignalComposer composer;
    private final Agent1SignalRepository repository;
    private final UpstoxHistoricalClient historicalClient;
    private final UpstoxOptionChainClient optionChainClient;
    private final FiiDiiService fiiDiiService;
    private final MarketauxClient marketauxClient;
    private final GiftNiftyClient giftNiftyClient;
    private final CommentaryExtractorService commentaryExtractor;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final ExpiryDateService expiryDateService;

    public ScoringPipeline(List<TierScorer> tierScorers,
                           SignalComposer composer,
                           Agent1SignalRepository repository,
                           UpstoxHistoricalClient historicalClient,
                           UpstoxOptionChainClient optionChainClient,
                           FiiDiiService fiiDiiService,
                           MarketauxClient marketauxClient,
                           GiftNiftyClient giftNiftyClient,
                           CommentaryExtractorService commentaryExtractor,
                           TechnicalIndicatorService technicalIndicatorService,
                           ExpiryDateService expiryDateService) {
        this.tierScorers = tierScorers;
        this.composer = composer;
        this.repository = repository;
        this.historicalClient = historicalClient;
        this.optionChainClient = optionChainClient;
        this.fiiDiiService = fiiDiiService;
        this.marketauxClient = marketauxClient;
        this.giftNiftyClient = giftNiftyClient;
        this.commentaryExtractor = commentaryExtractor;
        this.technicalIndicatorService = technicalIndicatorService;
        this.expiryDateService = expiryDateService;
    }

    /** Step 1-4 outside transaction (no DB writes during external API calls). Step 5 in transaction. */
    public Agent1SignalEntity run(ScoreRequestDto request) {
        LocalDateTime runTime = LocalDateTime.now();

        // Resolve expiry date: use caller-supplied value if present, otherwise auto-fetch next Tuesday expiry
        LocalDate effectiveExpiry = request.expiryDate() != null
                ? request.expiryDate()
                : expiryDateService.nextExpiry();
        if (effectiveExpiry == null) {
            throw new IllegalStateException("Cannot determine expiry date — Upstox unavailable and no expiry supplied");
        }
        log.info("pipeline.expiry resolved={} supplied={}", effectiveExpiry, request.expiryDate() != null);

        // Step 1: Fetch all inputs — each client handles its own errors, returns null on failure
        MarketInputs inputs = fetchInputs(request, effectiveExpiry);
        logInputsSummary(inputs);

        // Step 2: Score each tier
        List<TierScore> tierScores = tierScorers.stream()
                .map(scorer -> scorer.calculate(inputs))
                .toList();
        tierScores.forEach(t ->
            log.info("agent1.tier_result tier={} signals={} average={} contribution={}",
                    t.tierName(), t.signals(), t.average(), t.contribution()));

        // Step 3: Compose signal
        Agent1SignalEntity signal = composer.compose(tierScores, inputs, runTime);

        // Step 4: Attach JSON audit data
        signal.setScoreBreakdown(buildScoreBreakdownJson(tierScores));
        signal.setRawInputs(buildRawInputsJson(inputs));
        // BUG-05 fix: only persist key_levels when commentary was actually provided
        boolean hasCommentary = request.commentary() != null && !request.commentary().isBlank();
        signal.setKeyLevels(hasCommentary ? buildKeyLevelsJson(inputs.getCommentarySignal()) : null);
        signal.setDataGaps(buildDataGapsJson(inputs, hasCommentary && request.shouldFetchMarketaux()));

        // Step 5: Persist
        return persist(signal);
    }

    private MarketInputs fetchInputs(ScoreRequestDto request, LocalDate expiryDate) {
        // Historical candles for TA4J (200+ days)
        List<OhlcCandle> candles = safeGet(() -> historicalClient.fetchDailyCandles(200), List.of());

        // Pre-compute TA4J indicators from candle history
        PrecomputedIndicators indicators = technicalIndicatorService.compute(candles);

        // Fetch previous session's VIX close from Upstox historical candles.
        // More accurate than reading the DB signal's vix_level, which reflects the last Agent1 run
        // (an intraday value) rather than the true previous trading day close.
        BigDecimal lastVix = safeGet(historicalClient::fetchVixPrevClose, null);
        log.debug("pipeline.vixPrevLevel historical={}", lastVix);

        // Option chain: spot, PCR, max pain, futures premium from Upstox
        // Pass lastVix so VolatilityMacroScorer can calculate vix_daily_change (Tier 3)
        var chain = safeGet(() -> optionChainClient.fetch(expiryDate, lastVix), null);

        // Upstox FII/DII data — fetch, persist daily snapshots, and compute 5-day trend
        FiiDiiData fiiDii = safeGet(fiiDiiService::fetchAndPersist, null);

        // Marketaux news sentiment — fetch once, split into score (for scorer) + details (for audit/display)
        // Skipped only when caller explicitly sets fetchMarketaux=false (to conserve free-tier quota)
        NseiSentiment marketauxResult;
        if (request.shouldFetchMarketaux()) {
            marketauxResult = safeGet(marketauxClient::fetchNiftySentiment, null);
            log.debug("marketaux.fetch.result score={}", marketauxResult != null ? marketauxResult.averageScore() : "null");
        } else {
            log.info("marketaux.skipped — fetchMarketaux=false in request");
            marketauxResult = null;
        }
        BigDecimal marketauxSentiment = marketauxResult != null ? marketauxResult.averageScore() : null;

        // Gift Nifty premium vs Nifty previous close (Tier 3)
        BigDecimal giftNiftyPremium = null;
        BigDecimal giftNiftyLtp = safeGet(giftNiftyClient::fetchLtp, null);
        if (giftNiftyLtp != null && !candles.isEmpty()) {
            // candles[0] = most recent session close
            BigDecimal niftyPrevClose = candles.get(0).close();
            giftNiftyPremium = giftNiftyLtp.subtract(niftyPrevClose);
        }

        // LLM commentary extraction — keep full CommentarySignal for key_levels JSONB
        CommentarySignal commentarySignal = CommentarySignal.neutral();
        if (request.commentary() != null && !request.commentary().isBlank()) {
            commentarySignal = safeGet(
                    () -> commentaryExtractor.extract(request.commentary(), marketauxSentiment),
                    CommentarySignal.neutral());
        }

        return MarketInputs.builder()
                .spot(chain != null ? chain.spot() : null)
                .futuresPremium(chain != null ? chain.futuresPremium() : null)
                .pcr(chain != null ? chain.pcr() : null)
                .maxPain(chain != null ? chain.maxPain() : null)
                .vixLevel(chain != null ? chain.vixLevel() : null)
                .vixPrevLevel(chain != null ? chain.vixPrevLevel() : null)
                .vixRegime(chain != null ? chain.vixRegime() : VixRegime.NORMAL)
                .fiiNetFutures(fiiDii != null ? fiiDii.fiiNetFutures() : null)
                .fiiNetOptions(fiiDii != null ? fiiDii.fiiNetOptions() : null)
                .diiNet(fiiDii != null ? fiiDii.diiNet() : null)
                .fiiLongRatio(fiiDii != null ? fiiDii.fiiLongRatio() : null)
                .fiiTrend(fiiDii != null ? fiiDii.futuresTrend() : null)
                .callOiChange(chain != null ? chain.callOiChange() : null)
                .putOiChange(chain != null ? chain.putOiChange() : null)
                .giftNiftyPremium(giftNiftyPremium)
                .marketauxSentiment(marketauxSentiment)
                .marketauxDetails(marketauxResult)
                .commentaryBias(commentarySignal.bias())
                .commentarySignal(commentarySignal)
                .indicators(indicators)
                .expiryDate(expiryDate)
                .build();
    }

    private void logInputsSummary(MarketInputs i) {
        log.info("agent1.inputs.market  spot={} vix={} vixPrev={} vixRegime={} pcr={} maxPain={} futuresPremium={} giftNiftyPremium={}",
                i.getSpot(), i.getVixLevel(), i.getVixPrevLevel(), i.getVixRegime(),
                i.getPcr(), i.getMaxPain(), i.getFuturesPremium(), i.getGiftNiftyPremium());
        log.info("agent1.inputs.fii     fiiNetFutures={} fiiLongRatio={} fiiNetOptions={} diiNet={}",
                i.getFiiNetFutures(), i.getFiiLongRatio(), i.getFiiNetOptions(), i.getDiiNet());
        log.info("agent1.inputs.fii_trend {}", i.getFiiTrend() != null ? i.getFiiTrend().direction() + " avg5d=" + i.getFiiTrend().avgNetFlow5d() : "null");
        log.info("agent1.inputs.sentiment marketauxSentiment={} commentaryBias={}",
                i.getMarketauxSentiment(), i.getCommentaryBias());
        PrecomputedIndicators ind = i.getIndicators();
        log.info("agent1.inputs.indicators ema20={} ema50={} ema200={} rsi14={} macdLine={} macdSignal={} adx14={} bullishCandle={} bearishCandle={} higherHighs={} higherLows={}",
                ind.ema20(), ind.ema50(), ind.ema200(),
                ind.rsi14(), ind.macdLine(), ind.macdSignal(), ind.adx14(),
                ind.bullishCandlePattern(), ind.bearishCandlePattern(),
                ind.higherHighs(), ind.higherLows());
    }

    @Transactional
    protected Agent1SignalEntity persist(Agent1SignalEntity signal) {
        Agent1SignalEntity saved = repository.save(signal);
        log.info("agent1.signal.saved",
                kv("signalId", saved.getId()),
                kv("bias", saved.getBias()),
                kv("strength", saved.getStrength()),
                kv("compositeScore", saved.getCompositeScore()),
                kv("confidence", saved.getConfidence()));
        return saved;
    }

    private String buildScoreBreakdownJson(List<TierScore> tierScores) {
        // Simple JSON — full Jackson serialization wired in Agent1Service
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < tierScores.size(); i++) {
            TierScore t = tierScores.get(i);
            if (i > 0) sb.append(",");
            sb.append("\"").append(t.tierName()).append("\":{")
              .append("\"average\":").append(t.average()).append(",")
              .append("\"contribution\":").append(t.contribution()).append(",")
              .append("\"weight\":").append(t.weight())
              .append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildRawInputsJson(MarketInputs inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
          .append("\"spot\":").append(inputs.getSpot())
          .append(",\"vix\":").append(inputs.getVixLevel())
          .append(",\"pcr\":").append(inputs.getPcr())
          .append(",\"fiiLongRatio\":").append(inputs.getFiiLongRatio())
          .append(",\"diiNet\":").append(inputs.getDiiNet())
          .append(",\"marketauxSentiment\":").append(inputs.getMarketauxSentiment())
          .append(",\"fiiTrend\":").append(buildFiiTrendJson(inputs.getFiiTrend()));

        // Include full article details so the user can review and override the Tier 4 score
        NseiSentiment details = inputs.getMarketauxDetails();
        if (details != null && details.articles() != null) {
            sb.append(",\"marketauxArticles\":[");
            for (int i = 0; i < details.articles().size(); i++) {
                NseiSentiment.ArticleSummary a = details.articles().get(i);
                if (i > 0) sb.append(",");
                sb.append("{")
                  .append("\"title\":\"").append(escapeJson(a.title())).append("\"")
                  .append(",\"source\":\"").append(escapeJson(a.source())).append("\"")
                  .append(",\"publishedAt\":\"").append(a.publishedAt()).append("\"")
                  .append(",\"score\":").append(a.nseiSentimentScore())
                  .append(",\"url\":\"").append(escapeJson(a.url())).append("\"")
                  .append("}");
            }
            sb.append("]");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Builds the key_levels JSONB from the CommentarySignal extracted by the LLM.
     * Stored in agent1_signals.key_levels for Agent 2 to use as context.
     * null CommentarySignal → empty object (no levels extracted).
     */
    private String buildKeyLevelsJson(CommentarySignal signal) {
        if (signal == null) return "{}";

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"bias\":\"").append(signal.bias()).append("\"");
        sb.append(",\"conviction\":\"").append(signal.conviction()).append("\"");

        // niftySupport array
        sb.append(",\"niftySupport\":[");
        if (signal.niftySupport() != null) {
            for (int i = 0; i < signal.niftySupport().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(signal.niftySupport().get(i));
            }
        }
        sb.append("]");

        // niftyResistance array
        sb.append(",\"niftyResistance\":[");
        if (signal.niftyResistance() != null) {
            for (int i = 0; i < signal.niftyResistance().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(signal.niftyResistance().get(i));
            }
        }
        sb.append("]");

        if (signal.keyInsight() != null) {
            sb.append(",\"keyInsight\":\"").append(escapeJson(signal.keyInsight())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String buildFiiTrendJson(com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiTrend trend) {
        if (trend == null) return "null";
        return "{\"direction\":\"" + trend.direction() + "\""
                + ",\"avgNetFlow5d\":" + trend.avgNetFlow5d()
                + ",\"daysPositive\":" + trend.daysPositive()
                + ",\"daysNegative\":" + trend.daysNegative()
                + ",\"snapshotCount\":" + trend.snapshotCount()
                + "}";
    }

    /**
     * Builds a JSON array of input names that were null/unavailable during this scoring run.
     * Only checks top-level inputs; TA4J NaN indicators are tracked per-scorer.
     * Returns null (not stored) when all inputs were available.
     *
     * @param fetchedMarketaux true when marketaux fetch was requested (so a null value is a gap)
     */
    private static String buildDataGapsJson(MarketInputs inputs, boolean fetchedMarketaux) {
        List<String> gaps = new ArrayList<>();
        if (inputs.getSpot() == null)             gaps.add("SPOT");
        if (inputs.getVixLevel() == null)         gaps.add("VIX");
        if (inputs.getPcr() == null)              gaps.add("PCR");
        if (inputs.getFiiNetFutures() == null)    gaps.add("FII_FUTURES");
        if (inputs.getFiiNetOptions() == null)    gaps.add("FII_OPTIONS");
        if (inputs.getDiiNet() == null)           gaps.add("DII");
        if (inputs.getGiftNiftyPremium() == null) gaps.add("GIFT_NIFTY");
        if (fetchedMarketaux && inputs.getMarketauxSentiment() == null) gaps.add("MARKETAUX");
        if (gaps.isEmpty()) return null;
        return "[" + gaps.stream().map(g -> "\"" + g + "\"").collect(Collectors.joining(",")) + "]";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    @FunctionalInterface
    private interface DataFetcher<T> {
        T fetch() throws Exception;
    }

    private <T> T safeGet(DataFetcher<T> fetcher, T fallback) {
        try {
            return fetcher.fetch();
        } catch (Exception e) {
            log.warn("data.fetch.failed", kv("error", e.getMessage()));
            return fallback;
        }
    }
}
