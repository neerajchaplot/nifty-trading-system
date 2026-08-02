package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent1.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the per-tier weights used to compute the composite score.
 *
 * <p>Weights come from the single {@code "default"} user profile so the signal reflects the
 * user's preferences. If that profile is missing, or its five weights do not sum to 1.0000,
 * the system config weights ({@link TradingProperties}) are used instead — the scoring pipeline
 * never fails over a bad profile.
 *
 * <p>The resolved weights drive the composite once, so bias, strength AND confidence all follow
 * the user's weighting — there is no separate system-weighted computation.
 */
@Service
public class TierWeightResolver {

    private static final Logger log = LoggerFactory.getLogger(TierWeightResolver.class);

    // Must match TierScorer.getTierName() values.
    static final String T1A = "TIER_1A_PRICE_STRUCTURE";
    static final String T1B = "TIER_1B_TECHNICAL";
    static final String T2  = "TIER_2_INSTITUTIONAL_FLOW";
    static final String T3  = "TIER_3_VOLATILITY_MACRO";
    static final String T4  = "TIER_4_COMMENTARY_SENTIMENT";

    private static final String DEFAULT_USER_ID = "default";
    private static final BigDecimal WEIGHT_SUM_EXPECTED = new BigDecimal("1.0000");
    private static final BigDecimal SUM_TOLERANCE       = new BigDecimal("0.0010");

    public static final String SOURCE_USER_PROFILE  = "USER_PROFILE";
    public static final String SOURCE_SYSTEM_DEFAULT = "SYSTEM_DEFAULT";
    public static final String SOURCE_REQUEST_OVERRIDE = "REQUEST_OVERRIDE";

    private final UserProfileRepository repository;
    private final TradingProperties props;

    public TierWeightResolver(UserProfileRepository repository, TradingProperties props) {
        this.repository = repository;
        this.props = props;
    }

    /** Resolved weights plus where they came from (for audit/logging). */
    public record ResolvedWeights(Map<String, BigDecimal> byTier, String source) {}

    public ResolvedWeights resolve() {
        Map<String, BigDecimal> config = configWeights();

        Optional<UserProfileEntity> profile;
        try {
            profile = repository.findByUserId(DEFAULT_USER_ID);
        } catch (Exception e) {
            // A read failure must never break scoring — fall back to config.
            log.warn("agent1.weights.profile_read_failed reason={} — using system defaults", e.getMessage());
            return new ResolvedWeights(config, SOURCE_SYSTEM_DEFAULT);
        }

        if (profile.isEmpty()) {
            log.info("agent1.weights.no_profile userId={} — using system defaults", DEFAULT_USER_ID);
            return new ResolvedWeights(config, SOURCE_SYSTEM_DEFAULT);
        }

        Map<String, BigDecimal> userWeights = weightMap(profile.get());
        if (!isValid(userWeights)) {
            log.warn("agent1.weights.invalid_profile weights={} — using system defaults", userWeights);
            return new ResolvedWeights(config, SOURCE_SYSTEM_DEFAULT);
        }

        log.info("agent1.weights.resolved source={} weights={}", SOURCE_USER_PROFILE, userWeights);
        return new ResolvedWeights(userWeights, SOURCE_USER_PROFILE);
    }

    /**
     * Per-request override (e.g. the futures flow's commentary-heavy weighting). Applied only when
     * all five weights are present and sum to 1.0000 (± tolerance); otherwise falls back to
     * {@link #resolve()} so a bad override never breaks or silently skews scoring.
     */
    public ResolvedWeights resolveOverride(BigDecimal t1a, BigDecimal t1b, BigDecimal t2,
                                           BigDecimal t3, BigDecimal t4) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put(T1A, t1a);
        m.put(T1B, t1b);
        m.put(T2, t2);
        m.put(T3, t3);
        m.put(T4, t4);
        if (!isValid(m)) {
            log.warn("agent1.weights.invalid_override weights={} — falling back to profile/config", m);
            return resolve();
        }
        log.info("agent1.weights.resolved source={} weights={}", SOURCE_REQUEST_OVERRIDE, m);
        return new ResolvedWeights(m, SOURCE_REQUEST_OVERRIDE);
    }

    private Map<String, BigDecimal> configWeights() {
        TradingProperties.Scoring s = props.getScoring();
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put(T1A, s.getTier1aWeight());
        m.put(T1B, s.getTier1bWeight());
        m.put(T2,  s.getTier2Weight());
        m.put(T3,  s.getTier3Weight());
        m.put(T4,  s.getTier4Weight());
        return m;
    }

    private Map<String, BigDecimal> weightMap(UserProfileEntity p) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put(T1A, p.getTier1aWeight());
        m.put(T1B, p.getTier1bWeight());
        m.put(T2,  p.getTier2Weight());
        m.put(T3,  p.getTier3Weight());
        m.put(T4,  p.getTier4Weight());
        return m;
    }

    /** All five present and summing to 1.0000 (± tolerance). */
    private boolean isValid(Map<String, BigDecimal> weights) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal w : weights.values()) {
            if (w == null) return false;
            sum = sum.add(w);
        }
        return sum.subtract(WEIGHT_SUM_EXPECTED).abs().compareTo(SUM_TOLERANCE) <= 0;
    }
}
