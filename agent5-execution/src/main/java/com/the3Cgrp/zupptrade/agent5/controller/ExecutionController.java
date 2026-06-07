package com.the3Cgrp.zupptrade.agent5.controller;

import com.the3Cgrp.zupptrade.agent5.dto.*;
import com.the3Cgrp.zupptrade.agent5.service.TradeExecutionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Agent 5 REST API — Trade Execution.
 *
 * POST /api/v1/agent5/execute
 *   Called by orchestrator after Agent 2 /confirm.
 *   Blocking — returns when all legs are filled (or rejected).
 *   Typical response time: 5–35 seconds.
 *
 * POST /api/v1/agent5/exit/{tradeId}
 *   Called by Agent 3 on T3 breach or manual user exit.
 *   Places reverse MARKET orders on all legs.
 *
 * GET /api/v1/agent5/health
 *   Liveness probe.
 */
@RestController
@RequestMapping("/api/v1/agent5")
public class ExecutionController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionController.class);

    private final TradeExecutionService executionService;

    public ExecutionController(TradeExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/execute")
    public ResponseEntity<ExecuteTradeResponse> execute(@Valid @RequestBody ExecuteTradeRequest request) {
        log.info("api.execute", kv("tradeId", request.tradeId()), kv("legs", request.legs().size()));
        return ResponseEntity.ok(executionService.execute(request));
    }

    @PostMapping("/exit/{tradeId}")
    public ResponseEntity<ExitResponse> exit(@PathVariable UUID tradeId,
                                              @Valid @RequestBody ExitTradeRequest request) {
        if (!request.tradeId().equals(tradeId)) {
            return ResponseEntity.badRequest().build();
        }
        log.info("api.exit", kv("tradeId", tradeId), kv("reason", request.reason()));
        List<LegFillDto> fills = executionService.exit(request);
        return ResponseEntity.ok(new ExitResponse(tradeId, "CLOSED", fills, LocalDateTime.now()));
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("UP", LocalDateTime.now()));
    }

    public record ExitResponse(UUID tradeId, String status,
                                List<LegFillDto> exitFills, LocalDateTime closedAt) {}

    public record HealthResponse(String status, LocalDateTime timestamp) {}
}
