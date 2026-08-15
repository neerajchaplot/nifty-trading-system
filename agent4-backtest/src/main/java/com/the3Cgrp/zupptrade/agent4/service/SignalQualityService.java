package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.calculator.Agent1AccuracyCalculator;
import com.the3Cgrp.zupptrade.agent4.calculator.Agent1AccuracyCalculator.Thresholds;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.SignalQualityResponse;
import com.the3Cgrp.zupptrade.agent4.repository.AccuracyThresholdsRepository;
import com.the3Cgrp.zupptrade.agent4.repository.SignalQualityRepository;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class SignalQualityService {

    private static final Logger log = LoggerFactory.getLogger(SignalQualityService.class);

    private final SignalQualityRepository repository;
    private final AccuracyThresholdsRepository thresholdsRepository;
    private final OwnershipGuard guard;
    // Jackson 2 ObjectMapper — not a Spring Boot 4 bean (it uses Jackson 3), so created directly.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SignalQualityService(SignalQualityRepository repository,
                                AccuracyThresholdsRepository thresholdsRepository,
                                OwnershipGuard guard) {
        this.repository = repository;
        this.thresholdsRepository = thresholdsRepository;
        this.guard = guard;
    }

    public SignalQualityResponse getSignalQuality(LocalDate from, LocalDate to) {
        // Phase 5 scope: caller's profile id, or null for admin (all users). 401 if anonymous.
        List<Map<String, Object>> rows = repository.findSignals(from, to, guard.scopeProfileId());

        // Price-based accuracy: grade each signal on bias+strength vs its expiry-day move.
        LocalDate today = LocalDate.now();
        Thresholds thresholds = thresholdsRepository.get();

        int totalSignals         = rows.size();
        int signalsLeadingToTrade = (int) rows.stream()
                .filter(r -> r.get("trade_id") != null).count();
        int signalsSkipped       = totalSignals - signalsLeadingToTrade;

        BigDecimal overallAccuracy = Agent1AccuracyCalculator.accuracyRate(rows, today, thresholds);

        Map<String, BigDecimal> accuracyByConfidence = Agent1AccuracyCalculator
                .accuracyByGroup(rows, "confidence_label", today, thresholds);
        Map<String, BigDecimal> accuracyByBias = Agent1AccuracyCalculator
                .accuracyByGroup(rows, "bias", today, thresholds);

        // Commentary divergence: split accuracy — did Tier 4 divergence hurt the signal?
        Map<String, BigDecimal> divergenceImpact = new LinkedHashMap<>();
        List<Map<String, Object>> diverged = rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("commentary_divergence"))).toList();
        List<Map<String, Object>> aligned = rows.stream()
                .filter(r -> !Boolean.TRUE.equals(r.get("commentary_divergence"))).toList();

        divergenceImpact.put("diverged",
                Agent1AccuracyCalculator.accuracyRate(diverged, today, thresholds));
        divergenceImpact.put("aligned",
                Agent1AccuracyCalculator.accuracyRate(aligned, today, thresholds));

        String mostFrequentGap = findMostFrequentDataGap(rows);
        List<String> skipReasons = buildSkipReasons(rows, signalsSkipped);

        return new SignalQualityResponse(
                from,
                to,
                totalSignals,
                signalsLeadingToTrade,
                signalsSkipped,
                overallAccuracy,
                accuracyByConfidence,
                accuracyByBias,
                divergenceImpact,
                mostFrequentGap,
                skipReasons
        );
    }

    private String findMostFrequentDataGap(List<Map<String, Object>> rows) {
        Map<String, Long> gapCounts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String gapsJson = String.valueOf(row.getOrDefault("data_gaps_json", "[]"));
            try {
                List<String> gaps = objectMapper.readValue(gapsJson, new TypeReference<>() {});
                gaps.forEach(g -> gapCounts.merge(g, 1L, Long::sum));
            } catch (Exception e) {
                log.debug("Could not parse data_gaps for signal: {}", e.getMessage());
            }
        }
        return gapCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private List<String> buildSkipReasons(List<Map<String, Object>> rows, int skipped) {
        if (skipped == 0) return List.of();
        // Signals with no linked trade are the "skipped" ones — we record their outcome field
        return rows.stream()
                .filter(r -> r.get("trade_id") == null)
                .map(r -> {
                    String bias = String.valueOf(r.getOrDefault("bias", "UNKNOWN"));
                    String str  = String.valueOf(r.getOrDefault("strength", ""));
                    String vix  = String.valueOf(r.getOrDefault("vix_regime", ""));
                    return "BIAS:" + bias + "/" + str + " VIX:" + vix;
                })
                .distinct()
                .limit(20)
                .toList();
    }
}
