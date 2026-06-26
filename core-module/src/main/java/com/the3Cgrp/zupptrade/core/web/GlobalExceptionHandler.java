package com.the3Cgrp.zupptrade.core.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.Objects;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * System-wide RFC 9457 Problem Details error handler, auto-configured by core-module.
 *
 * Covers all infrastructure and generic exceptions. Runs at LOWEST_PRECEDENCE so
 * any agent-specific @RestControllerAdvice (e.g. TradeNotFoundException in agent2)
 * takes priority for its own exception types first.
 *
 * Agents must NOT duplicate these handlers — add a local @RestControllerAdvice only
 * for domain exceptions that are specific to that agent.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bad JSON body, wrong field types, null-into-primitive, etc. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMessageNotReadable(HttpMessageNotReadableException ex) {
        String detail = rootCauseMessage(ex);
        log.warn("request.body.invalid", kv("error", detail));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create("urn:zupptrade:error:bad-request"));
        pd.setTitle("Bad Request");
        return pd;
    }

    /** @NotNull, @NotBlank, @Valid failures on @RequestBody fields. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        log.warn("request.validation.failed", kv("detail", detail));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create("urn:zupptrade:error:validation"));
        pd.setTitle("Invalid Request");
        return pd;
    }

    /** GET on a POST endpoint, or any wrong HTTP method. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String detail = "Method '" + ex.getMethod() + "' not supported. Supported: " + ex.getSupportedHttpMethods();
        log.warn("request.method.not.supported", kv("method", ex.getMethod()));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, detail);
        pd.setType(URI.create("urn:zupptrade:error:method-not-allowed"));
        pd.setTitle("Method Not Allowed");
        return pd;
    }

    /** Unknown URL path — no matching endpoint. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        log.warn("request.path.not.found", kv("path", ex.getResourcePath()));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "No endpoint found for path: " + ex.getResourcePath());
        pd.setType(URI.create("urn:zupptrade:error:not-found"));
        pd.setTitle("Not Found");
        return pd;
    }

    /** Explicit bad-argument validation in service or controller code. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("request.invalid", kv("error", ex.getMessage()));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create("urn:zupptrade:error:bad-request"));
        pd.setTitle("Bad Request");
        return pd;
    }

    /** Illegal internal state — surfaced as 500 with the message for debuggability. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.error("internal.state.error", kv("error", ex.getMessage()));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        pd.setType(URI.create("urn:zupptrade:error:internal"));
        pd.setTitle("Internal Error");
        return pd;
    }

    /** Safety net — any unhandled exception returns 500 with the message. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("internal.error", kv("error", ex.getMessage()), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal error: " + ex.getMessage());
        pd.setType(URI.create("urn:zupptrade:error:internal"));
        pd.setTitle("Internal Server Error");
        return pd;
    }

    private static String rootCauseMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return Objects.requireNonNullElse(cause.getMessage(), ex.getMessage());
    }
}
