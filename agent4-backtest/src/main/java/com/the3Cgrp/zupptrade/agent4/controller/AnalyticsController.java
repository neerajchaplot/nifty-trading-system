package com.the3Cgrp.zupptrade.agent4.controller;

import com.the3Cgrp.zupptrade.agent4.config.AnalyticsConfig;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.*;
import com.the3Cgrp.zupptrade.agent4.repository.AnalyticsTradeRepository;
import com.the3Cgrp.zupptrade.agent4.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent4")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final PortfolioSummaryService summaryService;
    private final TradeListService        listService;
    private final TradeAuditService       auditService;
    private final SignalQualityService    signalQualityService;
    private final AnalyticsTradeRepository tradeRepository;
    private final AnalyticsConfig         config;

    public AnalyticsController(PortfolioSummaryService summaryService,
                               TradeListService listService,
                               TradeAuditService auditService,
                               SignalQualityService signalQualityService,
                               AnalyticsTradeRepository tradeRepository,
                               AnalyticsConfig config) {
        this.summaryService      = summaryService;
        this.listService         = listService;
        this.auditService        = auditService;
        this.signalQualityService = signalQualityService;
        this.tradeRepository     = tradeRepository;
        this.config              = config;
    }

    /**
     * Portfolio summary: win rate, total P&L, RoC, drawdown, adjustments, Agent 1 accuracy.
     * from/to are optional — null means all-time.
     */
    @GetMapping("/summary")
    public ResponseEntity<PortfolioSummaryResponse> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        validateDateRange(from, to);
        return ResponseEntity.ok(summaryService.getSummary(from, to));
    }

    /**
     * Paginated trade list. Defaults: page=0, size=5 (configurable).
     * Returns hasMore flag so UI can show "Load More" without knowing total upfront.
     */
    @GetMapping("/trades")
    public ResponseEntity<TradeListResponse> getTrades(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {

        validateDateRange(from, to);
        int pageSize = size != null ? size : config.getDefaultPageSize();
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        return ResponseEntity.ok(listService.getTrades(from, to, page, pageSize));
    }

    /**
     * Full audit of a single CLOSED trade: signal → recommendation → execution → monitoring timeline.
     */
    @GetMapping("/trades/{tradeId}/audit")
    public ResponseEntity<TradeAuditResponse> getAudit(@PathVariable UUID tradeId) {
        return ResponseEntity.ok(auditService.getAudit(tradeId));
    }

    /**
     * Agent 1 signal quality report: accuracy by confidence, by bias,
     * commentary divergence impact, most frequent data gap.
     */
    @GetMapping("/signal-quality")
    public ResponseEntity<SignalQualityResponse> getSignalQuality(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        validateDateRange(from, to);
        return ResponseEntity.ok(signalQualityService.getSignalQuality(from, to));
    }

    /**
     * Health check — confirms DB connectivity and reports closed trade count.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbOk = false;
        LocalDateTime lastTrade = null;
        long closedCount = 0;
        String error = null;
        try {
            closedCount = tradeRepository.countAllClosedTrades();
            Optional<Timestamp> ts = tradeRepository.findLastClosedTradeTimestamp();
            lastTrade = ts.map(Timestamp::toLocalDateTime).orElse(null);
            dbOk = true;
        } catch (Exception e) {
            error = e.getMessage();
            log.warn("Health check DB query failed: {}", e.getMessage());
        }

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("status",           dbOk ? "UP" : "DOWN");
        body.put("dbConnected",      dbOk);
        body.put("closedTradeCount", closedCount);
        body.put("lastTradeDate",    lastTrade != null ? lastTrade.toLocalDate().toString() : "none");
        if (error != null) body.put("error", error);
        return ResponseEntity.ok(body);
    }

    // ── Validation ────────────────────────────────────────────

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' date must not be after 'to' date");
        }
        if (from != null && to != null
                && from.plusDays(config.getMaxDateRangeDays()).isBefore(to)) {
            throw new IllegalArgumentException(
                    "Date range must not exceed " + config.getMaxDateRangeDays() + " days");
        }
    }
}
