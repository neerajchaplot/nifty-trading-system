package com.the3Cgrp.zupptrade.agent4.controller;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.FuturesPnlResponse;
import com.the3Cgrp.zupptrade.agent4.service.FuturesAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Agent 4 — futures P&L filter. Separate from the options analytics endpoints; reads CLOSED
 * futures trades only. from/to optional (null = all-time).
 */
@RestController
@RequestMapping("/api/v1/agent4/futures")
public class FuturesAnalyticsController {

    private final FuturesAnalyticsService service;

    public FuturesAnalyticsController(FuturesAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/trades")
    public ResponseEntity<FuturesPnlResponse> getFuturesTrades(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getFuturesPnl(from, to));
    }
}
