package com.the3Cgrp.zupptrade.agent3.controller;

import com.the3Cgrp.zupptrade.agent3.dto.ActiveTradeDto;
import com.the3Cgrp.zupptrade.agent3.dto.EvaluateOverrideRequest;
import com.the3Cgrp.zupptrade.agent3.dto.EvaluationResponse;
import com.the3Cgrp.zupptrade.agent3.service.ActiveTradesService;
import com.the3Cgrp.zupptrade.agent3.service.MonitorActionRouter;
import com.the3Cgrp.zupptrade.agent3.service.MonitorEvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
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

    private static final Logger log = LoggerFactory.getLogger(MonitorEvaluationController.class);

    private final MonitorEvaluationService evaluationService;
    private final ActiveTradesService activeTradesService;
    private final MonitorActionRouter actionRouter;
    private final boolean actAllowed;

    public MonitorEvaluationController(MonitorEvaluationService evaluationService,
                                       ActiveTradesService activeTradesService,
                                       MonitorActionRouter actionRouter,
                                       Environment environment) {
        this.evaluationService = evaluationService;
        this.activeTradesService = activeTradesService;
        this.actionRouter = actionRouter;
        // The act=true seam (routing a decision to Agent 5) is honored ONLY under the
        // sandbox or simulation profile — never in production.
        this.actAllowed = environment.acceptsProfiles(Profiles.of("sandbox", "simulation"));
    }

    /**
     * Evaluate a specific active trade.
     *
     * Optional request body: EvaluateOverrideRequest with niftySpot, vix, shortLegLtp,
     * longLegLtp, shortLegIv. When provided, these values replace the live Upstox fetch.
     * Intended for offline/weekend testing only — omit the body in production.
     */
    @PostMapping("/evaluate/{tradeId}")
    public ResponseEntity<EvaluationResponse> evaluate(
            @PathVariable UUID tradeId,
            @RequestParam(defaultValue = "false") boolean act,
            @RequestBody(required = false) @Nullable EvaluateOverrideRequest overrides) {
        EvaluationResponse response = evaluationService.evaluate(tradeId, overrides);
        // act=true routes the decision to Agent 5 (exit / readjust) using the SAME router as
        // the scheduler. Runs after evaluate() commits, so the Agent 5 call is outside the tx.
        if (act) {
            if (actAllowed) {
                log.warn("agent3.evaluate.act tradeId={} action={} — routing decision to Agent 5 (sandbox/simulation)",
                        tradeId, response.action());
                actionRouter.applyById(tradeId, response);
            } else {
                log.warn("agent3.evaluate.act_ignored tradeId={} — 'act=true' requires the sandbox or simulation profile; decision returned only",
                        tradeId);
            }
        }
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
