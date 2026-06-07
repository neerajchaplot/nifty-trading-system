package com.the3Cgrp.zupptrade.agent2.controller;

import com.the3Cgrp.zupptrade.agent2.service.RecommendationService;
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
     * Called by Agent 3 after Agent 5 confirms both leg fills.
     * Actual fill prices passed as headers to avoid them appearing in DB request logs.
     */
    @GetMapping("/monitor-config/{tradeId}")
    public ResponseEntity<MonitorConfigDto> monitorConfig(
            @PathVariable UUID tradeId,
            @RequestHeader("X-Short-Fill-Price") BigDecimal shortFillPrice,
            @RequestHeader("X-Long-Fill-Price") BigDecimal longFillPrice) {
        return ResponseEntity.ok(recommendationService.buildMonitorConfig(tradeId, shortFillPrice, longFillPrice));
    }
}
