package com.the3Cgrp.zupptrade.core.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The Upstox token needed to act is missing or expired. For simulation users this means the admin's
 * daily token hasn't been refreshed (Option B: block, don't degrade); for live users it means their
 * own session needs reconnecting. Maps to 503.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class BrokerUnavailableException extends RuntimeException {
    public BrokerUnavailableException(String message) {
        super(message);
    }
}
