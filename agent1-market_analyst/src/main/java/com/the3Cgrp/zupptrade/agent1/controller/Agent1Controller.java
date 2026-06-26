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

    /**
     * Returns the next upcoming Nifty expiry date.
     * Reads from reference_data cache (TTL 7 days); fetches from Upstox when stale.
     * The UI and Agent 2 use this to populate the expiry date selector.
     */
    @GetMapping("/next-expiry")
    public ResponseEntity<java.util.Map<String, Object>> nextExpiry() {
        LocalDate next = agent1Service.nextExpiry();
        if (next == null) {
            return ResponseEntity.status(503).body(java.util.Map.of(
                    "error", "Expiry date unavailable — Upstox unreachable and no cached data"));
        }
        return ResponseEntity.ok(java.util.Map.of(
                "nextExpiry", next,
                "allUpcoming", agent1Service.allUpcomingExpiries()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<HealthDto> health() {
        return ResponseEntity.ok(agent1Service.health());
    }
}
