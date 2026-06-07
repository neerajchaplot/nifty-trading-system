package com.the3Cgrp.zupptrade.agent1.scoring;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.model.MarketInputs;
import com.the3Cgrp.zupptrade.agent1.domain.model.TierScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tier 3 — Volatility and Macro (weight: 10%).
 * 3 signals: VIX daily change, OI change (call vs put), Gift Nifty premium.
 * Gift Nifty: stubbed score = 0 until data source confirmed (TODO).
 */
@Component
public class VolatilityMacroScorer implements TierScorer {

    private static final Logger log = LoggerFactory.getLogger(VolatilityMacroScorer.class);

    private final TradingProperties props;

    public VolatilityMacroScorer(TradingProperties props) {
        this.props = props;
    }

    @Override
    public String getTierName() { return "TIER_3_VOLATILITY_MACRO"; }

    @Override
    public BigDecimal getWeight() { return props.getScoring().getTier3Weight(); }

    @Override
    public TierScore calculate(MarketInputs inputs) {
        Map<String, Integer> signals = new LinkedHashMap<>();
        signals.put("vix_daily_change", voteVixChange(inputs.getVixLevel(), inputs.getVixPrevLevel()));
        signals.put("oi_change",        voteOiChange(inputs.getCallOiChange(), inputs.getPutOiChange()));
        signals.put("gift_nifty",       voteGiftNifty(inputs.getGiftNiftyPremium()));

        return buildTierScore(signals);
    }

    // --- package-private vote methods ---

    int voteVixChange(BigDecimal vixToday, BigDecimal vixPrev) {
        if (vixToday == null || vixPrev == null || vixPrev.compareTo(BigDecimal.ZERO) == 0) return 0;
        // pct change = (today - prev) / prev × 100
        BigDecimal pctChange = vixToday.subtract(vixPrev)
                .divide(vixPrev, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        if (pctChange.compareTo(props.getVix().getDailyChangeBullish()) < 0) return 1;  // VIX fell > 2% → bullish
        if (pctChange.compareTo(props.getVix().getDailyChangeBearish()) > 0) return -1; // VIX rose > 10% → bearish
        return 0;
    }

    int voteOiChange(BigDecimal callOiChange, BigDecimal putOiChange) {
        if (callOiChange == null || putOiChange == null) return 0;
        // More put OI building → market expecting support → bullish
        if (putOiChange.compareTo(callOiChange) > 0) return 1;
        if (callOiChange.compareTo(putOiChange) > 0) return -1;
        return 0;
    }

    int voteGiftNifty(BigDecimal giftNiftyPremium) {
        // TODO: Confirm Gift Nifty data source (SGX Nifty / CME Nifty futures).
        // Stubbing score = 0 until data feed is wired. See Q4 answer in design session.
        if (giftNiftyPremium == null) {
            return 0;
        }
        BigDecimal threshold = props.getGiftNifty().getSignificantPts();
        if (giftNiftyPremium.compareTo(threshold) > 0) return 1;
        if (giftNiftyPremium.negate().compareTo(threshold) > 0) return -1;
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
