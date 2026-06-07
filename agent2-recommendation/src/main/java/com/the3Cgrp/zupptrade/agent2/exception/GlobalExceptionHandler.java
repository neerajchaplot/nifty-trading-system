package com.the3Cgrp.zupptrade.agent2.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

import static net.logstash.logback.argument.StructuredArguments.kv;

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

    @ExceptionHandler(MarketDataUnavailableException.class)
    public ProblemDetail handleMarketDataUnavailable(MarketDataUnavailableException ex) {
        log.error("market.data.unavailable", kv("error", ex.getMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setType(URI.create("urn:zupptrade:error:market-data-unavailable"));
        problem.setTitle("Market Data Unavailable");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        log.warn("request.validation.failed", kv("detail", detail));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create("urn:zupptrade:error:validation"));
        problem.setTitle("Invalid Request");
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.error("internal.error", kv("error", ex.getMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problem.setType(URI.create("urn:zupptrade:error:internal"));
        problem.setTitle("Internal Error");
        return problem;
    }
}
