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
 * Tier 2 — Institutional Flow (weight: 30%).
 * Source: NSE FII/DII CSV (published ~7 PM previous day).
 * 4 signals. Null = data not yet available; scores 0 as per CLAUDE.md spec.
 */
@Component
public class InstitutionalFlowScorer implements TierScorer {

    private final TradingProperties props;

    public InstitutionalFlowScorer(TradingProperties props) {
        this.props = props;
    }

    @Override
    public String getTierName() { return "TIER_2_INSTITUTIONAL_FLOW"; }

    @Override
    public BigDecimal getWeight() { return props.getScoring().getTier2Weight(); }

    @Override
    public TierScore calculate(MarketInputs inputs) {
        Map<String, Integer> signals = new LinkedHashMap<>();
        signals.put("fii_net_futures",   voteFiiNetFutures(inputs.getFiiNetFutures()));
        signals.put("fii_long_ratio",    voteFiiLongRatio(inputs.getFiiLongRatio()));
        signals.put("fii_net_options",   voteFiiNetOptions(inputs.getFiiNetOptions()));
        signals.put("dii_net_cash",      voteDiiNet(inputs.getDiiNet()));

        return buildTierScore(signals);
    }

    // --- package-private vote methods ---

    int voteFiiNetFutures(BigDecimal fiiNetFutures) {
        if (fiiNetFutures == null) return 0;
        BigDecimal threshold = props.getFii().getSignificantFlowCrore();
        if (fiiNetFutures.compareTo(threshold) > 0) return 1;
        if (fiiNetFutures.negate().compareTo(threshold) > 0) return -1;
        return 0;
    }

    int voteFiiLongRatio(BigDecimal ratio) {
        if (ratio == null) return 0;
        if (ratio.compareTo(props.getFii().getLongRatioBullish()) > 0) return 1;
        if (ratio.compareTo(props.getFii().getLongRatioBearish()) < 0) return -1;
        return 0;
    }

    int voteFiiNetOptions(BigDecimal fiiNetOptions) {
        if (fiiNetOptions == null) return 0;
        BigDecimal threshold = props.getFii().getSignificantFlowCrore();
        // Net call buyer (positive) = bullish; net put buyer (negative) = bearish
        if (fiiNetOptions.compareTo(threshold) > 0) return 1;
        if (fiiNetOptions.negate().compareTo(threshold) > 0) return -1;
        return 0;
    }

    int voteDiiNet(BigDecimal diiNet) {
        if (diiNet == null) return 0;
        BigDecimal threshold = props.getScoring().getDiiSignificantCrore();
        if (diiNet.compareTo(threshold) > 0) return 1;
        if (diiNet.negate().compareTo(threshold) > 0) return -1;
        return 0;
    }

    private TierScore buildTierScore(Map<String, Integer> signals) {
        int sum = signals.values().stream().mapToInt(Integer::intValue).sum();
        BigDecimal average = BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(signals.size()), 4, RoundingMode.HALF_UP);
        BigDecimal contribution = average.multiply(getWeight()).setScale(4, RoundingMode.HALF_UP);
        return new TierScore(getTierName(), getWeight(), signals, average, contribution);
    }
}
