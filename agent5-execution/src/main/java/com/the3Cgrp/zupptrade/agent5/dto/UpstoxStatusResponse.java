package com.the3Cgrp.zupptrade.agent5.dto;

import java.time.LocalDateTime;

/**
 * Response from GET /api/v1/agent5/upstox/status.
 *
 * tokenStatus:
 *   LOADED      — token is present and Upstox accepted it (profile call succeeded)
 *   ABSENT      — no token in UpstoxTokenHolder (api_tokens table empty or UPSTOX_ACCESS_TOKEN not set)
 *   EXPIRED     — token present but Upstox returned 401 (run upstox-auth to refresh)
 *   UNREACHABLE — token present but Upstox API did not respond (network issue or timeout)
 */
public record UpstoxStatusResponse(
        String tokenStatus,
        boolean productionApiReachable,
        String userId,                   // from Upstox profile, null if not connected
        String userName,
        boolean sandboxTokenConfigured,  // true when UPSTOX_SANDBOX_TOKEN / order-access-token is set
        String orderGateway,             // api-hft.upstox.com (prod) or api-sandbox.upstox.com (sandbox)
        String errorDetail,              // null on success; human-readable error on failure
        LocalDateTime checkedAt
) {
    public static UpstoxStatusResponse connected(String userId, String userName,
                                                  boolean sandboxToken, String gateway) {
        return new UpstoxStatusResponse("LOADED", true, userId, userName,
                sandboxToken, gateway, null, LocalDateTime.now());
    }

    public static UpstoxStatusResponse tokenAbsent(String gateway) {
        return new UpstoxStatusResponse("ABSENT", false, null, null, false, gateway,
                "No access token — check api_tokens table or set UPSTOX_ACCESS_TOKEN", LocalDateTime.now());
    }

    public static UpstoxStatusResponse tokenExpired(String gateway) {
        return new UpstoxStatusResponse("EXPIRED", false, null, null, false, gateway,
                "Token rejected by Upstox (401) — run upstox-auth to refresh", LocalDateTime.now());
    }

    public static UpstoxStatusResponse unreachable(String detail, String gateway) {
        return new UpstoxStatusResponse("LOADED", false, null, null, false, gateway,
                "Upstox API unreachable: " + detail, LocalDateTime.now());
    }
}
