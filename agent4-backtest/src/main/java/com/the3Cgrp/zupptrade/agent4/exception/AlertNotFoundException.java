package com.the3Cgrp.zupptrade.agent4.exception;

import java.util.UUID;

/**
 * Thrown when an acknowledge is requested for a critical alert that is not in the LIVE state —
 * either it does not exist or it was already acknowledged. Mapped to 404 by the global handler.
 */
public class AlertNotFoundException extends RuntimeException {
    public AlertNotFoundException(UUID alertId) {
        super("No live critical alert found with id: " + alertId);
    }
}
