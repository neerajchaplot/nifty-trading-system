package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tier 4 — Commentary and News Sentiment (weight: 10%).
 * 2 signals: Marketaux ^NSEI sentiment average, LLM commentary extraction result.
 * LLM failure or missing input → score 0. Never propagates exceptions.
 */
@Component
public class CommentarySentimentScorer implements TierScorer {

    private final TradingProperties props;

    public CommentarySentimentScorer(TradingProperties props) {
        this.props = props;
    }

    @Override
    public String getTierName() { return "TIER_4_COMMENTARY_SENTIMENT"; }

    @Override
    public BigDecimal getWeight() { return props.getScoring().getTier4Weight(); }

    @Override
    public TierScore calculate(MarketInputs inputs) {
        Map<String, Integer> signals = new LinkedHashMap<>();
        signals.put("marketaux_sentiment", voteMarketaux(inputs.getMarketauxSentiment()));
        signals.put("llm_commentary",      voteCommentary(inputs.getCommentaryBias()));

        return buildTierScore(signals);
    }

    // --- package-private vote methods ---

    int voteMarketaux(BigDecimal sentiment) {
        if (sentiment == null) return 0;
        if (sentiment.compareTo(props.getScoring().getMarketauxBullish()) > 0) return 1;
        if (sentiment.compareTo(props.getScoring().getMarketauxBearish()) < 0) return -1;
        return 0;
    }

    int voteCommentary(String bias) {
        if (bias == null) return 0;
        return switch (bias.trim().toLowerCase()) {
            case "bullish" -> 1;
            case "bearish" -> -1;
            default -> 0;
        };
    }

    private TierScore buildTierScore(Map<String, Integer> signals) {
        int sum = signals.values().stream().mapToInt(Integer::intValue).sum();
        BigDecimal average = BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(signals.size()), 4, RoundingMode.HALF_UP);
        BigDecimal contribution = average.multiply(getWeight()).setScale(4, RoundingMode.HALF_UP);
        return new TierScore(getTierName(), getWeight(), signals, average, contribution);
    }
}
