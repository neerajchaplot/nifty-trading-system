package com.the3Cgrp.zupptrade.agent5.controller;

import com.the3Cgrp.zupptrade.agent5.dto.*;
import com.the3Cgrp.zupptrade.agent5.service.MarginCheckService;
import com.the3Cgrp.zupptrade.agent5.service.TradeExecutionService;
import com.the3Cgrp.zupptrade.agent5.service.UpstoxConnectionCheckService;
import com.the3Cgrp.zupptrade.shared.dto.ExitTradeRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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
 *   Liveness probe — includes DB ping and Upstox token status.
 *   Does NOT call the Upstox API; use /upstox/status for a live connectivity check.
 *
 * GET /api/v1/agent5/upstox/status
 *   Live Upstox connectivity check — calls GET /v2/user/profile.
 *   Use before a trading session to confirm the token is valid and Upstox is reachable.
 */
@RestController
@RequestMapping("/api/v1/agent5")
public class ExecutionController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionController.class);

    private final TradeExecutionService        executionService;
    private final UpstoxConnectionCheckService connectionCheckService;
    private final MarginCheckService           marginCheckService;
    private final JdbcTemplate                 jdbc;

    public ExecutionController(TradeExecutionService executionService,
                               UpstoxConnectionCheckService connectionCheckService,
                               MarginCheckService marginCheckService,
                               JdbcTemplate jdbc) {
        this.executionService        = executionService;
        this.connectionCheckService  = connectionCheckService;
        this.marginCheckService      = marginCheckService;
        this.jdbc                    = jdbc;
    }

    @PostMapping("/execute")
    public ResponseEntity<ExecuteTradeResponse> execute(@Valid @RequestBody ExecuteTradeRequest request) {
        log.info("api.execute", kv("tradeId", request.tradeId()), kv("legs", request.legs().size()));
        return ResponseEntity.ok(executionService.execute(request));
    }

    @PostMapping("/exit/{tradeId}")
    public ResponseEntity<ExitTradeResponse> exit(@PathVariable UUID tradeId,
                                                   @Valid @RequestBody ExitTradeRequest request) {
        if (!request.tradeId().equals(tradeId)) {
            return ResponseEntity.badRequest().build();
        }
        log.info("api.exit", kv("tradeId", tradeId), kv("reason", request.reason()));
        ExitTradeResponse response = executionService.exit(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Pre-execution margin check — called by the UI before Agent 2 /confirm in override flow.
     * Reads trade legs from DB, calls Upstox /v2/charges/margin + /v2/user/fund-and-margin,
     * and returns required vs available margin.
     *
     * POST (not GET) because it triggers two external Upstox API calls.
     */
    @PostMapping("/margin/check")
    public ResponseEntity<MarginCheckService.MarginCheckResultDto> marginCheck(
            @Valid @RequestBody MarginCheckService.MarginCheckRequestBody request) {
        log.info("api.margin.check",
                kv("tradeId", request.tradeId()), kv("overrideLots", request.overrideLots()));
        try {
            return ResponseEntity.ok(marginCheckService.check(request.tradeId(), request.overrideLots()));
        } catch (MarginCheckService.MarginCheckException e) {
            log.warn("api.margin.check.failed", kv("error", e.getMessage()));
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } catch (IllegalArgumentException e) {
            log.warn("api.margin.check.not_found", kv("error", e.getMessage()));
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Liveness probe — fast check, no external API calls.
     * Returns DEGRADED if the DB is unreachable; the app is still running.
     */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean dbOk         = pingDb();
        boolean tokenLoaded  = connectionCheckService.isTokenLoaded();
        String  status       = dbOk ? "UP" : "DEGRADED";
        return ResponseEntity.ok(new HealthResponse(status, LocalDateTime.now(), dbOk, tokenLoaded));
    }

    /**
     * Live Upstox connectivity check — calls GET /v2/user/profile (~200ms).
     * Call this before starting a trading session to confirm the connection is healthy.
     */
    @GetMapping("/upstox/status")
    public ResponseEntity<UpstoxStatusResponse> upstoxStatus() {
        log.info("api.upstox.status");
        return ResponseEntity.ok(connectionCheckService.check());
    }

    private boolean pingDb() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.warn("health.db.ping.failed error={}", e.getMessage());
            return false;
        }
    }

    public record HealthResponse(
            String status,
            LocalDateTime timestamp,
            boolean dbConnected,
            boolean upstoxTokenLoaded
    ) {}
}
