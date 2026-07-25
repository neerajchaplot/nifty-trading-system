package com.the3Cgrp.zupptrade.agent4.repository;

import com.the3Cgrp.zupptrade.agent4.calculator.Agent1AccuracyCalculator.Thresholds;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccuracyThresholdsRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccuracyThresholdsRepository repo = new AccuracyThresholdsRepository(jdbc);

    private void stubValue(String json) {
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn(json);
    }

    @Test
    void parsesFullThresholdRow() {
        stubValue("{\"extremePoints\": 200, \"mildPoints\": 100, \"neutralBandPoints\": 100}");
        Thresholds t = repo.get();
        assertThat(t.extremePoints()).isEqualByComparingTo("200");
        assertThat(t.mildPoints()).isEqualByComparingTo("100");
        assertThat(t.neutralBandPoints()).isEqualByComparingTo("100");
    }

    @Test
    void missingFieldsFallBackToDefaults() {
        // only mildPoints present → extreme and band use defaults (200 / 100)
        stubValue("{\"mildPoints\": 120}");
        Thresholds t = repo.get();
        assertThat(t.extremePoints()).isEqualByComparingTo("200");
        assertThat(t.mildPoints()).isEqualByComparingTo("120");
        assertThat(t.neutralBandPoints()).isEqualByComparingTo("100");
    }

    @Test
    void missingRowReturnsDefaults() {
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));
        Thresholds t = repo.get();
        assertThat(t.extremePoints()).isEqualByComparingTo("200");
        assertThat(t.mildPoints()).isEqualByComparingTo("100");
        assertThat(t.neutralBandPoints()).isEqualByComparingTo("100");
    }

    @Test
    void malformedJsonReturnsDefaults() {
        stubValue("this is not json");
        Thresholds t = repo.get();
        assertThat(t.extremePoints()).isEqualByComparingTo(new BigDecimal("200"));
        assertThat(t.mildPoints()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(t.neutralBandPoints()).isEqualByComparingTo(new BigDecimal("100"));
    }
}
