package com.the3Cgrp.zupptrade.agent1.controller;

import com.the3Cgrp.zupptrade.agent1.dto.HealthDto;
import com.the3Cgrp.zupptrade.agent1.dto.ScoreRequestDto;
import com.the3Cgrp.zupptrade.agent1.service.Agent1Service;
import com.the3Cgrp.zupptrade.shared.dto.Agent1SignalDto;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/agent1")
public class Agent1Controller {

    private final Agent1Service agent1Service;

    public Agent1Controller(Agent1Service agent1Service) {
        this.agent1Service = agent1Service;
    }

    @PostMapping("/score")
    public ResponseEntity<Agent1SignalDto> score(@Valid @RequestBody ScoreRequestDto request) {
        return ResponseEntity.ok(agent1Service.score(request));
    }

    @GetMapping("/latest")
    public ResponseEntity<Agent1SignalDto> latest(
            @RequestParam("expiry_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate) {
        return ResponseEntity.ok(agent1Service.latest(expiryDate));
    }

    @GetMapping("/health")
    public ResponseEntity<HealthDto> health() {
        return ResponseEntity.ok(agent1Service.health());
    }
}
