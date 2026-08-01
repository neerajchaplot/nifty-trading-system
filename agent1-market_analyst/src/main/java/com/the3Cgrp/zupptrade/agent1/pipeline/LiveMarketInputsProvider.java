package com.the3Cgrp.zupptrade.agent1.pipeline;

import com.the3Cgrp.zupptrade.agent1.client.GiftNiftyClient;
import com.the3Cgrp.zupptrade.agent1.client.MarketauxClient;
import com.the3Cgrp.zupptrade.agent1.client.MarketauxClient.NseiSentiment;
import com.the3Cgrp.zupptrade.agent1.client.UpstoxHistoricalClient;
import com.the3Cgrp.zupptrade.agent1.client.UpstoxOptionChainClient;
import com.the3Cgrp.zupptrade.agent1.domain.model.CommentarySignal;
import com.the3Cgrp.zupptrade.agent1.domain.model.FiiDiiData;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.OhlcCandle;
import com.the3Cgrp.zupptrade.agent1.domain.model.PrecomputedIndicators;
import com.the3Cgrp.zupptrade.agent1.dto.ScoreRequestDto;
import com.the3Cgrp.zupptrade.agent1.service.CommentaryExtractorService;
import com.the3Cgrp.zupptrade.agent1.service.FiiDiiService;
import com.the3Cgrp.zupptrade.agent1.service.NiftyCloseRecorderService;
import com.the3Cgrp.zupptrade.agent1.service.TechnicalIndicatorService;
import com.the3Cgrp.zupptrade.shared.enums.VixRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Production {@link MarketInputsProvider}: fetches every input from its live source
 * (Upstox candles + option chain, Upstox FII/DII, Marketaux, Gift Nifty, the LLM commentary
 * extractor) exactly as the pipeline always has. Active in every profile EXCEPT {@code simulation}.
 *
 * <p>This is the former {@code ScoringPipeline.fetchInputs} lifted verbatim into its own bean so
 * the pipeline can swap the data source without touching scoring logic.
 */
@Component
@Profile("!simulation")
public class LiveMarketInputsProvider implements MarketInputsProvider {

    private static final Logger log = LoggerFactory.getLogger(LiveMarketInputsProvider.class);

    private final UpstoxHistoricalClient historicalClient;
    private final UpstoxOptionChainClient optionChainClient;
    private final FiiDiiService fiiDiiService;
    private final MarketauxClient marketauxClient;
    private final GiftNiftyClient giftNiftyClient;
    private final CommentaryExtractorService commentaryExtractor;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final NiftyCloseRecorderService niftyCloseRecorder;

    public LiveMarketInputsProvider(UpstoxHistoricalClient historicalClient,
                                    UpstoxOptionChainClient optionChainClient,
                                    FiiDiiService fiiDiiService,
                                    MarketauxClient marketauxClient,
                                    GiftNiftyClient giftNiftyClient,
                                    CommentaryExtractorService commentaryExtractor,
                                    TechnicalIndicatorService technicalIndicatorService,
                                    NiftyCloseRecorderService niftyCloseRecorder) {
        this.historicalClient = historicalClient;
        this.optionChainClient = optionChainClient;
        this.fiiDiiService = fiiDiiService;
        this.marketauxClient = marketauxClient;
        this.giftNiftyClient = giftNiftyClient;
        this.commentaryExtractor = commentaryExtractor;
        this.technicalIndicatorService = technicalIndicatorService;
        this.niftyCloseRecorder = niftyCloseRecorder;
    }

    @Override
    public MarketInputs fetch(ScoreRequestDto request, LocalDate expiryDate) {
        // Historical candles for TA4J (200+ days)
        List<OhlcCandle> candles = safeGet(() -> historicalClient.fetchDailyCandles(200), List.of());

        // Record settled daily closes as a byproduct (no extra Upstox call) for Agent 4's
        // signal-accuracy metric. Best-effort — never breaks scoring.
        niftyCloseRecorder.record(candles);

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
        boolean hasCommentary = request.commentary() != null && !request.commentary().isBlank();
        CommentarySignal commentarySignal = CommentarySignal.neutral();
        if (hasCommentary) {
            commentarySignal = safeGet(
                    () -> commentaryExtractor.extract(request.commentary(), marketauxSentiment),
                    CommentarySignal.neutral());
        }
        // commentaryBias == null signals Tier 4 that NO user commentary was provided, so it takes
        // Marketaux at face value instead of averaging with a 0 LLM vote. A genuinely NEUTRAL user
        // view still yields a non-null "NEUTRAL" bias and keeps the two-signal average.
        String commentaryBias = hasCommentary ? commentarySignal.bias() : null;

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
                .commentaryBias(commentaryBias)
                .commentarySignal(commentarySignal)
                .indicators(indicators)
                .expiryDate(expiryDate)
                .build();
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
