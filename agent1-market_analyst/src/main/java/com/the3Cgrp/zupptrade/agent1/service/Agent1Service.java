package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.domain.entity.Agent1SignalEntity;
import com.the3Cgrp.zupptrade.agent1.dto.HealthDto;
import com.the3Cgrp.zupptrade.agent1.dto.ScoreRequestDto;
import com.the3Cgrp.zupptrade.agent1.pipeline.ScoringPipeline;
import com.the3Cgrp.zupptrade.agent1.repository.Agent1SignalRepository;
import com.the3Cgrp.zupptrade.shared.dto.Agent1SignalDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class Agent1Service {

    private final ScoringPipeline pipeline;
    private final Agent1SignalRepository repository;

    public Agent1Service(ScoringPipeline pipeline, Agent1SignalRepository repository) {
        this.pipeline = pipeline;
        this.repository = repository;
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

    public HealthDto health() {
        return repository.findAll().stream()
                .max(java.util.Comparator.comparing(Agent1SignalEntity::getTimestamp))
                .map(e -> new HealthDto("UP", e.getTimestamp(),
                        e.getBias().name(), e.getConfidence().name()))
                .orElse(new HealthDto("UP", null, null, null));
    }

    private Agent1SignalDto toDto(Agent1SignalEntity e) {
        return new Agent1SignalDto(
                e.getId(), e.getTimestamp(), e.getExpiryDate(),
                e.getBias(), e.getStrength(),
                e.getCompositeScore(), e.getConfidenceScore(), e.getConfidence(),
                e.getVixLevel(), e.getVixRegime(), e.getVixDirection(),
                e.getScoreBreakdown(), e.getCommentaryDivergence(), e.getKeyLevels()
        );
    }
}
