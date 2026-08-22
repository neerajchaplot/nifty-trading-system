package com.the3Cgrp.zupptrade.agent1.pipeline;

import com.the3Cgrp.zupptrade.agent1.client.MarketauxClient.NseiSentiment;
import com.the3Cgrp.zupptrade.agent1.composer.SignalComposer;
import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.CommentarySignal;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.PrecomputedIndicators;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import com.the3Cgrp.zupptrade.agent1.dto.ScoreRequestDto;
import com.the3Cgrp.zupptrade.agent1.explain.SignalExplanationService;
import com.the3Cgrp.zupptrade.agent1.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.agent1.scoring.TierScorer;
import com.the3Cgrp.zupptrade.agent1.service.TierWeightResolver;
import com.the3Cgrp.zupptrade.core.expiry.ExpiryDateService;
import com.the3Cgrp.zupptrade.core.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final MarketInputsProvider marketInputsProvider;
    private final ExpiryDateService expiryDateService;
    private final TierWeightResolver tierWeightResolver;
    private final SignalExplanationService explanationService;
    private final UserContext userContext;

    public ScoringPipeline(List<TierScorer> tierScorers,
                           SignalComposer composer,
                           Agent1SignalRepository repository,
                           MarketInputsProvider marketInputsProvider,
                           ExpiryDateService expiryDateService,
                           TierWeightResolver tierWeightResolver,
                           SignalExplanationService explanationService,
                           UserContext userContext) {
        this.tierScorers = tierScorers;
        this.composer = composer;
        this.repository = repository;
        this.marketInputsProvider = marketInputsProvider;
        this.expiryDateService = expiryDateService;
        this.tierWeightResolver = tierWeightResolver;
        this.explanationService = explanationService;
        this.userContext = userContext;
    }

    /** Step 1-4 outside transaction (no DB writes during external API calls). Step 5 in transaction. */
    public Agent1SignalEntity run(ScoreRequestDto request) {
        // Capture in UTC (not the JVM default zone) so the persisted wall-clock is
        // unambiguous — the container runs UTC while dev machines run IST, and a
        // zoneless timestamp was being misread downstream. See Agent1SignalDto.timestamp.
        LocalDateTime runTime = LocalDateTime.now(ZoneOffset.UTC);

        // Resolve expiry date: use caller-supplied value if present, otherwise auto-fetch next Tuesday expiry
        LocalDate effectiveExpiry = request.expiryDate() != null
                ? request.expiryDate()
                : expiryDateService.nextExpiry();
        if (effectiveExpiry == null) {
            throw new IllegalStateException("Cannot determine expiry date — Upstox unavailable and no expiry supplied");
        }
        log.info("pipeline.expiry resolved={} supplied={}", effectiveExpiry, request.expiryDate() != null);

        // Step 1: Fetch all inputs via the provider (live Upstox/Marketaux/LLM, or scenario folder
        // under the simulation profile). The provider never throws — missing data becomes null.
        MarketInputs inputs = marketInputsProvider.fetch(request, effectiveExpiry);
        logInputsSummary(inputs);

        // Step 2: Score each tier
        List<TierScore> tierScores = tierScorers.stream()
                .map(scorer -> scorer.calculate(inputs))
                .toList();
        tierScores.forEach(t ->
            log.info("agent1.tier_result tier={} signals={} average={} contribution={}",
                    t.tierName(), t.signals(), t.average(), t.contribution()));

        // Resolve per-tier weights, and drive the composite ONCE so bias, strength and confidence all
        // follow the same weighting. Two flows:
        //   FUTURES  → explicit per-request override (commentary-heavy) supplied in the request body.
        //   TRADING  → the acting user's own profile weights (null user → system config defaults).
        TierWeightResolver.ResolvedWeights weights =
                (request.weights() != null && request.weights().complete())
                        ? tierWeightResolver.resolveOverride(
                                request.weights().tier1a(), request.weights().tier1b(),
                                request.weights().tier2(), request.weights().tier3(), request.weights().tier4())
                        : tierWeightResolver.resolve(
                                userContext.current().map(u -> u.profileId()).orElse(null));
        log.info("agent1.weights source={} weights={}", weights.source(), weights.byTier());

        // Step 3: Compose signal using the resolved weights
        Agent1SignalEntity signal = composer.compose(tierScores, inputs, runTime, weights.byTier());

        // Step 4: Attach JSON audit data (score_breakdown reflects the weights actually used)
        signal.setScoreBreakdown(buildScoreBreakdownJson(tierScores, weights.byTier()));
        signal.setRawInputs(buildRawInputsJson(inputs));
        // BUG-05 fix: only persist key_levels when commentary was actually provided
        boolean hasCommentary = request.commentary() != null && !request.commentary().isBlank();
        signal.setKeyLevels(hasCommentary ? buildKeyLevelsJson(inputs.getCommentarySignal()) : null);
        List<String> dataGaps = collectDataGaps(inputs, hasCommentary && request.shouldFetchMarketaux());
        signal.setDataGaps(dataGaps.isEmpty() ? null : toJsonArray(dataGaps));

        // Plain-English explanation (deterministic, best-effort — never blocks the pipeline).
        signal.setExplanation(explanationService.build(signal, tierScores, dataGaps));

        // Channel (TRADING vs FUTURES) so /latest keeps the two tabs' signals separate.
        signal.setSource(request.effectiveSource());

        // Multi-user (Phase 5): stamp the acting user so /latest can scope to them. Scheduled/house
        // runs have no UserContext → null owner (admins still see it; per-user reads do not).
        userContext.current().ifPresent(u -> signal.setUserProfileId(u.profileId()));

        // Step 5: Persist
        return persist(signal);
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

    private String buildScoreBreakdownJson(List<TierScore> tierScores, Map<String, BigDecimal> weightByTier) {
        // Simple JSON — full Jackson serialization wired in Agent1Service.
        // Weight and contribution reflect the resolved (user or config) weights actually applied,
        // so summing the five contributions reproduces composite_score.
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < tierScores.size(); i++) {
            TierScore t = tierScores.get(i);
            BigDecimal weight = weightByTier == null ? null : weightByTier.get(t.tierName());
            if (weight == null) weight = t.weight();
            BigDecimal contribution = t.average().multiply(weight).setScale(4, RoundingMode.HALF_UP);
            if (i > 0) sb.append(",");
            sb.append("\"").append(t.tierName()).append("\":{")
              .append("\"average\":").append(t.average()).append(",")
              .append("\"contribution\":").append(contribution).append(",")
              .append("\"weight\":").append(weight)
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
    /**
     * Collects the input names that were null/unavailable during this scoring run.
     * Only checks top-level inputs; TA4J NaN indicators are tracked per-scorer.
     * Shared by the data_gaps JSON and the plain-English explanation so the two never drift.
     *
     * @param fetchedMarketaux true when marketaux fetch was requested (so a null value is a gap)
     */
    private static List<String> collectDataGaps(MarketInputs inputs, boolean fetchedMarketaux) {
        List<String> gaps = new ArrayList<>();
        if (inputs.getSpot() == null)             gaps.add("SPOT");
        if (inputs.getVixLevel() == null)         gaps.add("VIX");
        if (inputs.getPcr() == null)              gaps.add("PCR");
        if (inputs.getFiiNetFutures() == null)    gaps.add("FII_FUTURES");
        if (inputs.getFiiNetOptions() == null)    gaps.add("FII_OPTIONS");
        if (inputs.getDiiNet() == null)           gaps.add("DII");
        if (inputs.getGiftNiftyPremium() == null) gaps.add("GIFT_NIFTY");
        if (fetchedMarketaux && inputs.getMarketauxSentiment() == null) gaps.add("MARKETAUX");
        return gaps;
    }

    private static String toJsonArray(List<String> values) {
        return "[" + values.stream().map(g -> "\"" + g + "\"").collect(Collectors.joining(",")) + "]";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
