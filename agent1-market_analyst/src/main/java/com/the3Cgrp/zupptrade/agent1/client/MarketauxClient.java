package com.the3Cgrp.zupptrade.agent1.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Fetches news sentiment for ^NSEI from the Marketaux REST API.
 *
 * Endpoint: GET /v1/news/all?symbols=^NSEI&api_token={key}&language=en&limit=3
 *
 * Returns a {@link NseiSentiment} result containing:
 *   - averageScore  — used by Tier 4 scorer (> +0.30 bullish, < -0.30 bearish)
 *   - articles      — individual article titles, URLs, per-article scores and source
 *                     shown to the end user so they can override the automated score if needed
 *
 * Free tier: 100 req/day.
 * Result is cached for 30 minutes via Caffeine — see spring.cache.caffeine.spec in application.yml.
 * Returns null on any error; caller treats null as score 0 (neutral).
 */
@Component
public class MarketauxClient {

    private static final Logger log = LoggerFactory.getLogger(MarketauxClient.class);
    private static final String NSEI_SYMBOL = "^NSEI";

    private final RestClient marketauxRestClient;
    private final String apiKey;

    public MarketauxClient(@Qualifier("marketauxRestClient") RestClient marketauxRestClient,
                           @Value("${marketaux.api.key}") String apiKey) {
        this.marketauxRestClient = marketauxRestClient;
        this.apiKey = apiKey;
    }

    /**
     * Fetches the latest 3 articles tagged ^NSEI and returns sentiment details.
     * Result is cached for 30 minutes.
     *
     * @return full sentiment result including per-article details, or null if unavailable
     */
    @Cacheable(value = "marketaux-sentiment", key = "'nsei'", unless = "#result == null")
    public NseiSentiment fetchNiftySentiment() {
        try {
            MarketauxResponse response = marketauxRestClient.get()
                    .uri("/v1/news/all?symbols={symbol}&api_token={key}&language=en&limit=3",
                            NSEI_SYMBOL, apiKey)
                    .retrieve()
                    .body(MarketauxResponse.class);

            if (response == null || response.data() == null || response.data().isEmpty()) {
                log.warn("marketaux.empty_response");
                return null;
            }

            // Build per-article summaries, extracting the ^NSEI entity score for each article
            List<NseiSentiment.ArticleSummary> articles = response.data().stream()
                    .map(article -> {
                        BigDecimal nseiScore = article.entities() == null ? null :
                                article.entities().stream()
                                        .filter(e -> NSEI_SYMBOL.equals(e.symbol()) && e.sentimentScore() != null)
                                        .map(MarketauxEntity::sentimentScore)
                                        .findFirst()
                                        .orElse(null);

                        return new NseiSentiment.ArticleSummary(
                                article.title(),
                                article.url(),
                                article.source(),
                                article.publishedAt(),
                                article.snippet(),
                                nseiScore
                        );
                    })
                    .toList();

            // Average score across articles that have a ^NSEI entity score
            List<BigDecimal> scores = articles.stream()
                    .map(NseiSentiment.ArticleSummary::nseiSentimentScore)
                    .filter(s -> s != null)
                    .toList();

            if (scores.isEmpty()) {
                List<String> allSymbols = response.data().stream()
                        .filter(a -> a.entities() != null)
                        .flatMap(a -> a.entities().stream())
                        .map(MarketauxEntity::symbol)
                        .distinct()
                        .toList();
                log.warn("marketaux.no_nsei_entities articles={} all_symbols_found={}",
                        response.data().size(), allSymbols);
                return null;
            }

            BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(scores.size()), 4, RoundingMode.HALF_UP);

            log.info("marketaux.sentiment.fetched avg_score={} matched_articles={} total_articles={}",
                    avg, scores.size(), articles.size());

            return new NseiSentiment(avg, articles);

        } catch (Exception e) {
            log.warn("marketaux.fetch.error error={}", e.getMessage(), e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Public result type — returned to callers and shown in the scoring output
    // -------------------------------------------------------------------------

    /**
     * Full sentiment result for ^NSEI.
     *
     * @param averageScore     average ^NSEI entity sentiment across matched articles [-1..+1]
     * @param articles         individual article details for user review and potential override
     */
    public record NseiSentiment(
            BigDecimal averageScore,
            List<ArticleSummary> articles
    ) {
        /**
         * One news article contributing to the sentiment score.
         *
         * @param title              article headline
         * @param url                full article URL — user can click through to read
         * @param source             publication name (e.g. "economictimes.indiatimes.com")
         * @param publishedAt        UTC publication timestamp
         * @param snippet            short excerpt from the article body
         * @param nseiSentimentScore ^NSEI entity sentiment for this article, null if not tagged
         */
        public record ArticleSummary(
                String title,
                String url,
                String source,
                OffsetDateTime publishedAt,
                String snippet,
                BigDecimal nseiSentimentScore
        ) {}
    }

    // -------------------------------------------------------------------------
    // Marketaux API response model
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MarketauxResponse(List<MarketauxArticle> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MarketauxArticle(
            String title,
            String url,
            String source,
            String snippet,
            @JsonProperty("published_at") OffsetDateTime publishedAt,
            List<MarketauxEntity> entities
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MarketauxEntity(
            String symbol,
            @JsonProperty("sentiment_score") BigDecimal sentimentScore
    ) {}
}
