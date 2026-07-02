package com.the3Cgrp.zupptrade.agent2.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Agent 2 domain exception handlers.
 * Infrastructure and generic exceptions (bad JSON, 405, 404, etc.) are handled
 * by core-module's GlobalExceptionHandler auto-configuration.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TradeNotFoundException.class)
    public ProblemDetail handleTradeNotFound(TradeNotFoundException ex) {
        log.warn("trade.not.found", kv("tradeId", ex.getTradeId()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("urn:zupptrade:error:trade-not-found"));
        problem.setTitle("Trade Not Found");
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.warn("trade.invalid.state", kv("error", ex.getMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("urn:zupptrade:error:invalid-trade-state"));
        problem.setTitle("Invalid Trade State");
        return problem;
    }

    @ExceptionHandler(MarketDataUnavailableException.class)
    public ProblemDetail handleMarketDataUnavailable(MarketDataUnavailableException ex) {
        log.error("market.data.unavailable", kv("error", ex.getMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setType(URI.create("urn:zupptrade:error:market-data-unavailable"));
        problem.setTitle("Market Data Unavailable");
        return problem;
    }
}
