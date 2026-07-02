package com.the3Cgrp.zupptrade.agent2.controller;

import com.the3Cgrp.zupptrade.agent2.service.RecommendationService;
import com.the3Cgrp.zupptrade.shared.dto.CalculateOverrideRequestDto;
import com.the3Cgrp.zupptrade.shared.dto.CalculateOverrideResultDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorConfigDto;
import com.the3Cgrp.zupptrade.shared.dto.RecommendRequestDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeConfirmRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent2")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @PostMapping("/recommend")
    public ResponseEntity<TradeCardDto> recommend(@Valid @RequestBody RecommendRequestDto request) {
        return ResponseEntity.ok(recommendationService.recommend(request));
    }

    @PostMapping("/confirm")
    public ResponseEntity<TradeCardDto> confirm(@Valid @RequestBody TradeConfirmRequestDto request) {
        return ResponseEntity.ok(recommendationService.confirm(request));
    }

    /**
     * Stateless recalculation for the manual override builder.
     * Fetches live LTP from Upstox for the requested strikes, runs Black-Scholes PoP,
     * and returns metrics. Nothing is persisted. Called on 500ms debounce as user edits fields.
     */
    @PostMapping("/calculate-override")
    public ResponseEntity<CalculateOverrideResultDto> calculateOverride(
            @Valid @RequestBody CalculateOverrideRequestDto request) {
        return ResponseEntity.ok(recommendationService.calculateOverride(request));
    }

    /**
     * Re-seeds monitor_config for a trade using LTPs stored in trade.legs.
     * Call this after deploying threshold fixes to repair existing active trades.
     */
    @PostMapping("/refresh-monitor/{tradeId}")
    public ResponseEntity<MonitorConfigDto> refreshMonitor(@PathVariable UUID tradeId) {
        return ResponseEntity.ok(recommendationService.refreshMonitorConfig(tradeId));
    }

    /**
     * Called by Agent 3 after Agent 5 confirms fills.
     * Fill prices passed as headers (not request body) to avoid appearing in DB request logs.
     *
     * 2-leg spreads: X-Short-Fill-Price + X-Long-Fill-Price only.
     * Iron Condor: all four headers required — X-Short/Long-Fill-Price for PE, X-CE-Short/Long-Fill-Price for CE.
     */
    @GetMapping("/monitor-config/{tradeId}")
    public ResponseEntity<MonitorConfigDto> monitorConfig(
            @PathVariable UUID tradeId,
            @RequestHeader("X-Short-Fill-Price") BigDecimal shortFillPrice,
            @RequestHeader("X-Long-Fill-Price") BigDecimal longFillPrice,
            @RequestHeader(value = "X-CE-Short-Fill-Price", required = false) BigDecimal ceShortFillPrice,
            @RequestHeader(value = "X-CE-Long-Fill-Price",  required = false) BigDecimal ceLongFillPrice) {
        return ResponseEntity.ok(recommendationService.buildMonitorConfig(
                tradeId, shortFillPrice, longFillPrice, ceShortFillPrice, ceLongFillPrice));
    }
}
