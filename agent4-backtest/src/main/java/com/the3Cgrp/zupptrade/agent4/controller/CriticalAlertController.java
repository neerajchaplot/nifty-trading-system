package com.the3Cgrp.zupptrade.agent4.controller;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.CriticalAlertDto;
import com.the3Cgrp.zupptrade.agent4.service.CriticalAlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Critical-alert API: surfaces user-actionable alerts (critical_alerts table) on the Trading
 * dashboard and lets the user acknowledge them. Sits alongside the read-only analytics endpoints
 * under the same /api/v1/agent4 base path.
 */
@RestController
@RequestMapping("/api/v1/agent4")
public class CriticalAlertController {

    private final CriticalAlertService service;

    public CriticalAlertController(CriticalAlertService service) {
        this.service = service;
    }

    /** LIVE (unacknowledged) critical alerts, newest first. */
    @GetMapping("/critical-alerts")
    public ResponseEntity<List<CriticalAlertDto>> getLiveAlerts() {
        return ResponseEntity.ok(service.getLiveAlerts());
    }

    /**
     * Acknowledge a single alert (LIVE → ACKNOWLEDGED). Returns 404 (Problem Detail) if the alert
     * does not exist or was already acknowledged.
     */
    @PostMapping("/critical-alerts/{alertId}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledge(@PathVariable UUID alertId) {
        service.acknowledge(alertId);
        return ResponseEntity.ok(Map.of("alertId", alertId, "status", "ACKNOWLEDGED"));
    }
}
