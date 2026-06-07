package com.the3Cgrp.zupptrade.agent5.dto;

import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Input to POST /api/v1/agent5/exit/{tradeId}.
 *
 * Called by Agent 3 on T3 EXIT breach, VIX spike > 30%, or manual user exit.
 * Agent 5 places reverse MARKET orders for each leg to close the position.
 *
 * exitLegs: sourced from entry_fills stored in trades table.
 *   originalAction: the action at entry. Agent 5 reverses it (BUY→SELL, SELL→BUY).
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
