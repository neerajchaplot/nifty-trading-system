package com.the3Cgrp.zupptrade.agent2.controller;

import com.the3Cgrp.zupptrade.agent2.service.FuturesRecommendationService;
import com.the3Cgrp.zupptrade.shared.dto.FuturesConfirmRequestDto;
import com.the3Cgrp.zupptrade.shared.dto.FuturesPlanCardDto;
import com.the3Cgrp.zupptrade.shared.dto.FuturesRecommendRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 2 — Nifty FUTURES intraday trade plan (spec ZUPPTRADE_FUTURES_TRADING_SPEC).
 * Separate from the options endpoints; reuses the same Agent 1 signal.
 */
@RestController
@RequestMapping("/api/v1/agent2/futures")
public class FuturesRecommendationController {

    private final FuturesRecommendationService service;

    public FuturesRecommendationController(FuturesRecommendationService service) {
        this.service = service;
    }

    /**
     * Builds the full multi-arm trade card (all four arms + every calculation) and persists it
     * as a PRIMED plan. The end user then selects which arm to arm via /confirm.
     */
    @PostMapping("/recommend")
    public ResponseEntity<FuturesPlanCardDto> recommend(@Valid @RequestBody FuturesRecommendRequestDto request) {
        return ResponseEntity.ok(service.recommend(request));
    }

    /**
     * User's decision on a primed plan. APPROVE + selectedArm → dormant ARMED plan
     * (Agent 3 then watches the entry trigger); REJECT → REJECTED.
     */
    @PostMapping("/confirm")
    public ResponseEntity<FuturesPlanCardDto> confirm(@Valid @RequestBody FuturesConfirmRequestDto request) {
        return ResponseEntity.ok(service.confirm(request));
    }

    /**
     * Screen 2 — accepted futures plans for today: dormant (ARMED/BREAK_DETECTED, greyed until
     * the entry trigger fires) and active (CONFIRMED/FILLED once Agent 5 submits the GTT).
     */
    @GetMapping("/plans/active")
    public ResponseEntity<List<FuturesPlanCardDto>> activePlans() {
        return ResponseEntity.ok(service.listActive());
    }
}
