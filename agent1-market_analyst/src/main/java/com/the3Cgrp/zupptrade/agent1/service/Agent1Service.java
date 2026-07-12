package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent1.dto.HealthDto;
import com.the3Cgrp.zupptrade.agent1.dto.ScoreRequestDto;
import com.the3Cgrp.zupptrade.agent1.pipeline.ScoringPipeline;
import com.the3Cgrp.zupptrade.agent1.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.core.expiry.ExpiryDateService;
import com.the3Cgrp.zupptrade.shared.dto.Agent1SignalDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class Agent1Service {

    private final ScoringPipeline pipeline;
    private final Agent1SignalRepository repository;
    private final ExpiryDateService expiryDateService;

    public Agent1Service(ScoringPipeline pipeline,
                         Agent1SignalRepository repository,
                         ExpiryDateService expiryDateService) {
        this.pipeline = pipeline;
        this.repository = repository;
        this.expiryDateService = expiryDateService;
    }

    public Agent1SignalDto score(ScoreRequestDto request) {
        Agent1SignalEntity entity = pipeline.run(request);
        return toDto(entity);
    }

    public Agent1SignalDto latest(LocalDate expiryDate) {
        return repository
                .findTopByExpiryDateAndStatusOrderByTimestampDesc(expiryDate, "ACTIVE")
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active signal found for expiry: " + expiryDate));
    }

    /** Returns the next upcoming Nifty expiry date from the DB cache (or Upstox if cache is stale). */
    public LocalDate nextExpiry() {
        return expiryDateService.nextExpiry();
    }

    /** Returns all upcoming Nifty expiry dates (sorted ascending). */
    public List<LocalDate> allUpcomingExpiries() {
        LocalDate today = LocalDate.now();
        return expiryDateService.allExpiries().stream()
                .filter(d -> !d.isBefore(today))
                .toList();
    }

    public HealthDto health() {
        return repository.findAll().stream()
                .max(java.util.Comparator.comparing(Agent1SignalEntity::getTimestamp))
                .map(e -> new HealthDto("UP", e.getTimestamp(),
                        e.getBias().name(), e.getConfidence().name()))
                .orElse(new HealthDto("UP", null, null, null));
    }

    private Agent1SignalDto toDto(Agent1SignalEntity e) {
        // The stored timestamp is a UTC wall-clock LocalDateTime (written via
        // LocalDateTime.now(UTC)); attach the UTC offset so it serialises as "…Z".
        OffsetDateTime timestamp = e.getTimestamp() == null
                ? null
                : e.getTimestamp().atOffset(ZoneOffset.UTC);
        return new Agent1SignalDto(
                e.getId(), timestamp, e.getExpiryDate(),
                e.getBias(), e.getStrength(),
                e.getCompositeScore(), e.getConfidenceScore(), e.getConfidence(),
                e.getVixLevel(), e.getVixRegime(), e.getVixDirection(),
                e.getScoreBreakdown(), e.getCommentaryDivergence(), e.getKeyLevels(),
                e.getDataGaps(), e.getSpot()
        );
    }
}
