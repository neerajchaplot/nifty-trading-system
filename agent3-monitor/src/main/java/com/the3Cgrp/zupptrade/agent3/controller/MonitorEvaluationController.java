package com.the3Cgrp.zupptrade.agent3.controller;

import com.the3Cgrp.zupptrade.agent3.dto.ActiveTradeDto;
import com.the3Cgrp.zupptrade.agent3.dto.EvaluationResponse;
import com.the3Cgrp.zupptrade.agent3.service.ActiveTradesService;
import com.the3Cgrp.zupptrade.agent3.service.MonitorEvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * POST /api/v1/agent3/evaluate/{tradeId}  — per-trade evaluation (called by orchestrator every 5 min)
 * GET  /api/v1/agent3/active-trades       — list all ACTIVE/EXIT_FAILED trades for the UI live monitor
 *
 * Agent 3 owns the monitoring state, so it is the natural home for the active trades query.
 */
@RestController
@RequestMapping("/api/v1/agent3")
public class MonitorEvaluationController {

    private final MonitorEvaluationService evaluationService;
    private final ActiveTradesService activeTradesService;

    public MonitorEvaluationController(MonitorEvaluationService evaluationService,
                                       ActiveTradesService activeTradesService) {
        this.evaluationService = evaluationService;
        this.activeTradesService = activeTradesService;
    }

    /**
     * Evaluate a specific active trade.
     * @param tradeId UUID of the trade to evaluate
     * @return EvaluationResponse with action, reason, P&L, and live PoP
     */
    @PostMapping("/evaluate/{tradeId}")
    public ResponseEntity<EvaluationResponse> evaluate(@PathVariable UUID tradeId) {
        EvaluationResponse response = evaluationService.evaluate(tradeId);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns all trades in ACTIVE or EXIT_FAILED status, enriched with
     * the latest monitoring evaluation snapshot (P&L, spot, threshold state).
     * Polled by the UI every 5–10 seconds for the Live Trades Monitor panel.
     */
    @GetMapping("/active-trades")
    public ResponseEntity<List<ActiveTradeDto>> activeTrades() {
        return ResponseEntity.ok(activeTradesService.findAllActive());
    }

    /** Health check endpoint. */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("{\"status\":\"UP\"}");
    }
}
