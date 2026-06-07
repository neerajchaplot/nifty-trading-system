package com.the3Cgrp.zupptrade.core.upstox.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Generic envelope for all Upstox v2 API responses: { "status": "success", "data": {...} } */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxApiResponse<T>(String status, T data) {
    public boolean isSuccess() { return "success".equalsIgnoreCase(status); }
}
