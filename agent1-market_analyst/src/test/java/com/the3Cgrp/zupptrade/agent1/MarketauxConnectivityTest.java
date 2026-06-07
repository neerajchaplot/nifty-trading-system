package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.agent1.client.MarketauxClient;
import com.the3Cgrp.zupptrade.agent1.client.MarketauxClient.NseiSentiment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — verifies the real Marketaux API call, ^NSEI sentiment parsing,
 * and that article details are returned for end-user review.
 *
 * HOW TO RUN:
 *   mvn test -pl agent1-market_analyst "-Dexcluded.test.groups=" -Dgroups=integration "-Dspring.profiles.active=local"
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
class MarketauxConnectivityTest {

    @Autowired private MarketauxClient marketauxClient;

    @Test
    void marketauxSentiment_returnsScoreAndArticleDetails() {
        NseiSentiment result = marketauxClient.fetchNiftySentiment();

        System.out.println("\n=== Marketaux ^NSEI Sentiment ===");

        assertThat(result)
                .as("Expected sentiment result — check WARN log for 'all_symbols_found' if null")
                .isNotNull();

        System.out.printf("Average score : %s%n", result.averageScore());
        String label = result.averageScore().compareTo(new BigDecimal("0.30"))  > 0 ? "BULLISH (+1)" :
                       result.averageScore().compareTo(new BigDecimal("-0.30")) < 0 ? "BEARISH (-1)" : "NEUTRAL (0)";
        System.out.printf("Tier 4 signal : %s%n", label);
        System.out.printf("Articles      : %d%n%n", result.articles().size());

        // Print each article so the user can decide whether to override the automated score
        for (int i = 0; i < result.articles().size(); i++) {
            NseiSentiment.ArticleSummary a = result.articles().get(i);
            System.out.printf("--- Article %d ---%n", i + 1);
            System.out.printf("  Title     : %s%n", a.title());
            System.out.printf("  Source    : %s%n", a.source());
            System.out.printf("  Published : %s%n", a.publishedAt());
            System.out.printf("  Score     : %s%n", a.nseiSentimentScore() != null ? a.nseiSentimentScore() : "n/a (not tagged ^NSEI)");
            System.out.printf("  Snippet   : %s%n", a.snippet() != null
                    ? a.snippet().substring(0, Math.min(a.snippet().length(), 200)) + "..."
                    : "n/a");
            System.out.printf("  URL       : %s%n%n", a.url());
        }

        assertThat(result.averageScore())
                .as("Sentiment score must be in [-1, +1]")
                .isBetween(new BigDecimal("-1.0"), new BigDecimal("1.0"));
        assertThat(result.articles()).isNotEmpty();

        System.out.println("✓ Marketaux OK — score=" + result.averageScore() + " articles=" + result.articles().size());
    }

    @Test
    void marketauxSentiment_isCached() {
        NseiSentiment first  = marketauxClient.fetchNiftySentiment();
        NseiSentiment second = marketauxClient.fetchNiftySentiment();

        System.out.println("\n=== Marketaux Cache Check ===");
        System.out.println("First call  score: " + (first  != null ? first.averageScore()  : "null"));
        System.out.println("Second call score: " + (second != null ? second.averageScore() : "null"));

        // Same instance from cache — not a new API call
        assertThat(second).isSameAs(first);
        System.out.println("✓ Cache working — same object instance returned on second call");
    }
}
