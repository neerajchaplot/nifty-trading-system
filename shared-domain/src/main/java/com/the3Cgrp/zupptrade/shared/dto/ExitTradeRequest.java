package com.the3Cgrp.zupptrade.shared.dto;

import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Input to POST /api/v1/agent5/exit/{tradeId}.
 *
 * Placed in shared-domain so Agent 3 can build the request without
 * a compile-time dependency on agent5-execution.
 *
 * exitLegs: sourced from MonitorConfigDto (shortLeg + longLeg).
 *   originalAction: the action at ENTRY. Agent 5 reverses it (SELL→BUY, BUY→SELL).
 */
public record ExitTradeRequest(

        @NotNull
        UUID tradeId,

        @NotBlank
        String reason,   // e.g. "T3_EXIT_BREACH", "VIX_SPIKE", "MANUAL_EXIT"

        @NotNull
        @NotEmpty
        List<ExitLeg> exitLegs

) {
    public record ExitLeg(
            @NotBlank String instrumentKey,
            @NotNull  LegAction originalAction,  // the action to REVERSE
            int quantity
    ) {}
}
