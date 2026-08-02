package com.the3Cgrp.zupptrade.agent5.controller;

import com.the3Cgrp.zupptrade.agent5.dto.FuturesCloseResponse;
import com.the3Cgrp.zupptrade.agent5.dto.FuturesGttRequest;
import com.the3Cgrp.zupptrade.agent5.dto.FuturesGttResponse;
import com.the3Cgrp.zupptrade.agent5.service.FuturesCloseService;
import com.the3Cgrp.zupptrade.agent5.service.FuturesExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Agent 5 — Nifty FUTURES execution. Places the multi-leg GTT (OCO) for a CONFIRMED plan.
 * Called by Agent 3 once its entry state machine confirms the break.
 */
@RestController
@RequestMapping("/api/v1/agent5/futures")
public class FuturesExecutionController {

    private final FuturesExecutionService service;
    private final FuturesCloseService closeService;

    public FuturesExecutionController(FuturesExecutionService service, FuturesCloseService closeService) {
        this.service = service;
        this.closeService = closeService;
    }

    @PostMapping("/gtt")
    public ResponseEntity<FuturesGttResponse> placeGtt(@Valid @RequestBody FuturesGttRequest request) {
        return ResponseEntity.ok(service.placeGtt(request.planId()));
    }

    /** Called by Agent 3's 15:30 EOD scheduler to book realized P&L for a FILLED plan by its tag. */
    @PostMapping("/close/{planId}")
    public ResponseEntity<FuturesCloseResponse> close(@PathVariable UUID planId) {
        return ResponseEntity.ok(closeService.closePlan(planId));
    }
}
