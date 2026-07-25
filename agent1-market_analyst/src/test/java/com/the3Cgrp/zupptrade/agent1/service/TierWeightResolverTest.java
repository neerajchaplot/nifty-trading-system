package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.config.TradingProperties;
import com.the3Cgrp.zupptrade.agent1.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent1.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies weight resolution precedence: a valid "default" profile wins; otherwise the system
 * config weights are used. Scoring must never fail because of a missing or malformed profile.
 */
class TierWeightResolverTest {

    private UserProfileRepository repository;
    private TradingProperties props;
    private TierWeightResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(UserProfileRepository.class);
        props = new TradingProperties();
        resolver = new TierWeightResolver(repository, props);
    }

    @Test
    void validDefaultProfile_usesProfileWeights() {
        UserProfileEntity p = profile("0.10", "0.10", "0.60", "0.10", "0.10");
        when(repository.findByUserId("default")).thenReturn(Optional.of(p));

        TierWeightResolver.ResolvedWeights r = resolver.resolve();

        assertThat(r.source()).isEqualTo(TierWeightResolver.SOURCE_USER_PROFILE);
        assertThat(r.byTier().get(TierWeightResolver.T2)).isEqualByComparingTo("0.60");
        assertThat(r.byTier().get(TierWeightResolver.T1A)).isEqualByComparingTo("0.10");
    }

    @Test
    void noProfile_fallsBackToConfigDefaults() {
        when(repository.findByUserId("default")).thenReturn(Optional.empty());

        TierWeightResolver.ResolvedWeights r = resolver.resolve();

        assertThat(r.source()).isEqualTo(TierWeightResolver.SOURCE_SYSTEM_DEFAULT);
        assertThat(r.byTier().get(TierWeightResolver.T1A)).isEqualByComparingTo("0.30");
        assertThat(r.byTier().get(TierWeightResolver.T1B)).isEqualByComparingTo("0.20");
        assertThat(r.byTier().get(TierWeightResolver.T2)).isEqualByComparingTo("0.30");
        assertThat(r.byTier().get(TierWeightResolver.T3)).isEqualByComparingTo("0.10");
        assertThat(r.byTier().get(TierWeightResolver.T4)).isEqualByComparingTo("0.10");
    }

    @Test
    void profileWeightsNotSummingToOne_fallsBackToConfigDefaults() {
        // Sums to 1.50 — malformed; must not be trusted.
        UserProfileEntity p = profile("0.30", "0.30", "0.30", "0.30", "0.30");
        when(repository.findByUserId("default")).thenReturn(Optional.of(p));

        TierWeightResolver.ResolvedWeights r = resolver.resolve();

        assertThat(r.source()).isEqualTo(TierWeightResolver.SOURCE_SYSTEM_DEFAULT);
        assertThat(r.byTier().get(TierWeightResolver.T2)).isEqualByComparingTo("0.30");
    }

    @Test
    void repositoryFailure_fallsBackToConfigDefaults() {
        when(repository.findByUserId("default")).thenThrow(new RuntimeException("db down"));

        TierWeightResolver.ResolvedWeights r = resolver.resolve();

        assertThat(r.source()).isEqualTo(TierWeightResolver.SOURCE_SYSTEM_DEFAULT);
    }

    private static UserProfileEntity profile(String w1a, String w1b, String w2, String w3, String w4) {
        UserProfileEntity p = mock(UserProfileEntity.class);
        when(p.getTier1aWeight()).thenReturn(new BigDecimal(w1a));
        when(p.getTier1bWeight()).thenReturn(new BigDecimal(w1b));
        when(p.getTier2Weight()).thenReturn(new BigDecimal(w2));
        when(p.getTier3Weight()).thenReturn(new BigDecimal(w3));
        when(p.getTier4Weight()).thenReturn(new BigDecimal(w4));
        return p;
    }
}
