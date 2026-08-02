package com.the3Cgrp.zupptrade.agent5.dto;

import java.util.UUID;

/** Result of a futures GTT placement. status = FILLED (placed) | EXECUTION_FAILED. */
public record FuturesGttResponse(
        UUID planId,
        String gttOrderId,
        String status,
        String message
) {}
