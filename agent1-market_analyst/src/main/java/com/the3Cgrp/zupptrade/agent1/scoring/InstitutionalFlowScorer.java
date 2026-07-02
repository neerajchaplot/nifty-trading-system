package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tier 2 — Institutional Flow (weight: 30%).
 * Source: Upstox /v2/market/fii + /v2/market/dii.
 * 4 signals. Null = data not yet available; scores 0 as per CLAUDE.md spec.
 */
@Component
public class InstitutionalFlowScorer implements TierScorer {

    private static final Logger log = LoggerFactory.getLogger(InstitutionalFlowScorer.class);

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
        int vFiiNet  = voteFiiNetFutures(inputs.getFiiNetFutures());
        int vLongRat = voteFiiLongRatio(inputs.getFiiLongRatio());
        int vFiiOpt  = voteFiiNetOptions(inputs.getFiiNetOptions());
        int vDii     = voteDiiNet(inputs.getDiiNet());

        BigDecimal threshold = props.getFii().getSignificantFlowCrore();
        log.info("agent1.tier2 fii_net_futures:  value={} Cr  threshold=±{} Cr  → {}", inputs.getFiiNetFutures(), threshold, vLabel(vFiiNet));
        log.info("agent1.tier2 fii_long_ratio:   ratio={}  (>{}=bull, <{}=bear)  → {}", inputs.getFiiLongRatio(), props.getFii().getLongRatioBullish(), props.getFii().getLongRatioBearish(), vLabel(vLongRat));
        log.info("agent1.tier2 fii_net_options:  value={} Cr  threshold=±{} Cr  → {}", inputs.getFiiNetOptions(), threshold, vLabel(vFiiOpt));
        log.info("agent1.tier2 dii_net_cash:     value={} Cr  threshold=±{} Cr  → {}", inputs.getDiiNet(), props.getScoring().getDiiSignificantCrore(), vLabel(vDii));

        Map<String, Integer> signals = new LinkedHashMap<>();
        signals.put("fii_net_futures",  vFiiNet);
        signals.put("fii_long_ratio",   vLongRat);
        signals.put("fii_net_options",  vFiiOpt);
        signals.put("dii_net_cash",     vDii);

        return buildTierScore(signals);
    }

    private static String vLabel(int v) {
        return v == 1 ? "+1 (BULLISH)" : v == -1 ? "-1 (BEARISH)" : "0 (NEUTRAL)";
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
